/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.core;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.Map;
import org.apache.beam.runners.core.construction.ElementAndRestriction;
import org.apache.beam.runners.core.construction.PTransformReplacements;
import org.apache.beam.runners.core.construction.PTransformTranslation.RawPTransform;
import org.apache.beam.runners.core.construction.ReplacementOutputs;
import org.apache.beam.runners.core.construction.SplittableParDo;
import org.apache.beam.runners.core.construction.SplittableParDo.ProcessKeyedElements;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.runners.PTransformOverrideFactory;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.state.WatermarkHoldState;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.TimestampCombiner;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.joda.time.Instant;

/**
 * Utilities for implementing {@link ProcessKeyedElements} using {@link KeyedWorkItem} and
 * runner-specific {@link StateInternals} and {@link TimerInternals}.
 */
public class SplittableParDoViaKeyedWorkItems {
  /**
   * Runner-specific primitive {@link GroupByKey GroupByKey-like} {@link PTransform} that produces
   * {@link KeyedWorkItem KeyedWorkItems} so that downstream transforms can access state and timers.
   *
   * <p>Unlike a real {@link GroupByKey}, ignores the input's windowing and triggering strategy and
   * emits output immediately.
   */
  public static class GBKIntoKeyedWorkItems<KeyT, InputT>
      extends RawPTransform<
          PCollection<KV<KeyT, InputT>>, PCollection<KeyedWorkItem<KeyT, InputT>>> {
    @Override
    public PCollection<KeyedWorkItem<KeyT, InputT>> expand(PCollection<KV<KeyT, InputT>> input) {
      return PCollection.createPrimitiveOutputInternal(
          input.getPipeline(), WindowingStrategy.globalDefault(), input.isBounded());
    }

    @Override
    public String getUrn() {
      return SplittableParDo.SPLITTABLE_GBKIKWI_URN;
    }
  }

  /** Overrides a {@link ProcessKeyedElements} into {@link SplittableProcessViaKeyedWorkItems}. */
  public static class OverrideFactory<InputT, OutputT, RestrictionT>
      implements PTransformOverrideFactory<
          PCollection<KV<String, ElementAndRestriction<InputT, RestrictionT>>>, PCollectionTuple,
      ProcessKeyedElements<InputT, OutputT, RestrictionT>> {
    @Override
    public PTransformReplacement<
            PCollection<KV<String, ElementAndRestriction<InputT, RestrictionT>>>, PCollectionTuple>
        getReplacementTransform(
            AppliedPTransform<
                    PCollection<KV<String, ElementAndRestriction<InputT, RestrictionT>>>,
                    PCollectionTuple, ProcessKeyedElements<InputT, OutputT, RestrictionT>>
                transform) {
      return PTransformReplacement.of(
          PTransformReplacements.getSingletonMainInput(transform),
          new SplittableProcessViaKeyedWorkItems<>(transform.getTransform()));
    }

    @Override
    public Map<PValue, ReplacementOutput> mapOutputs(
        Map<TupleTag<?>, PValue> outputs, PCollectionTuple newOutput) {
      return ReplacementOutputs.tagged(outputs, newOutput);
    }
  }

  /**
   * Runner-specific primitive {@link PTransform} that invokes the {@link DoFn.ProcessElement}
   * method for a splittable {@link DoFn}.
   */
  public static class SplittableProcessViaKeyedWorkItems<InputT, OutputT, RestrictionT>
      extends PTransform<
          PCollection<KV<String, ElementAndRestriction<InputT, RestrictionT>>>, PCollectionTuple> {
    private final ProcessKeyedElements<InputT, OutputT, RestrictionT> original;

    public SplittableProcessViaKeyedWorkItems(
        ProcessKeyedElements<InputT, OutputT, RestrictionT> original) {
      this.original = original;
    }

    @Override
    public PCollectionTuple expand(
        PCollection<KV<String, ElementAndRestriction<InputT, RestrictionT>>> input) {
      return input
          .apply(new GBKIntoKeyedWorkItems<String, ElementAndRestriction<InputT, RestrictionT>>())
          .setCoder(
              KeyedWorkItemCoder.of(
                  StringUtf8Coder.of(),
                  ((KvCoder<String, ElementAndRestriction<InputT, RestrictionT>>) input.getCoder())
                      .getValueCoder(),
                  input.getWindowingStrategy().getWindowFn().windowCoder()))
          .apply(new ProcessElements<>(original));
    }
  }

  /** A primitive transform wrapping around {@link ProcessFn}. */
  public static class ProcessElements<
          InputT, OutputT, RestrictionT, TrackerT extends RestrictionTracker<RestrictionT>>
      extends PTransform<
          PCollection<KeyedWorkItem<String, ElementAndRestriction<InputT, RestrictionT>>>,
          PCollectionTuple> {
    private final ProcessKeyedElements<InputT, OutputT, RestrictionT> original;

    public ProcessElements(ProcessKeyedElements<InputT, OutputT, RestrictionT> original) {
      this.original = original;
    }

    public ProcessFn<InputT, OutputT, RestrictionT, TrackerT> newProcessFn(
        DoFn<InputT, OutputT> fn) {
      return new ProcessFn<>(
          fn,
          original.getElementCoder(),
          original.getRestrictionCoder(),
          original.getInputWindowingStrategy());
    }

    public DoFn<InputT, OutputT> getFn() {
      return original.getFn();
    }

    public List<PCollectionView<?>> getSideInputs() {
      return original.getSideInputs();
    }

    public TupleTag<OutputT> getMainOutputTag() {
      return original.getMainOutputTag();
    }

    public TupleTagList getAdditionalOutputTags() {
      return original.getAdditionalOutputTags();
    }

    @Override
    public PCollectionTuple expand(
        PCollection<KeyedWorkItem<String, ElementAndRestriction<InputT, RestrictionT>>> input) {
      return ProcessKeyedElements.createPrimitiveOutputFor(
          input,
          original.getFn(),
          original.getMainOutputTag(),
          original.getAdditionalOutputTags(),
          original.getInputWindowingStrategy());
    }
  }

  /**
   * The heart of splittable {@link DoFn} execution: processes a single (element, restriction) pair
   * by creating a tracker for the restriction and checkpointing/resuming processing later if
   * necessary.
   *
   * <p>Takes {@link KeyedWorkItem} and assumes that the KeyedWorkItem contains a single element (or
   * a single timer set by {@link ProcessFn itself}, in a single window. This is necessary because
   * {@link ProcessFn} sets timers, and timers are namespaced to a single window and it should be
   * the window of the input element.
   *
   * <p>See also: https://issues.apache.org/jira/browse/BEAM-1983
   */
  @VisibleForTesting
  public static class ProcessFn<
          InputT, OutputT, RestrictionT, TrackerT extends RestrictionTracker<RestrictionT>>
      extends DoFn<KeyedWorkItem<String, ElementAndRestriction<InputT, RestrictionT>>, OutputT> {
    /**
     * The state cell containing a watermark hold for the output of this {@link DoFn}. The hold is
     * acquired during the first {@link DoFn.ProcessElement} call for each element and restriction,
     * and is released when the {@link DoFn.ProcessElement} call returns and there is no residual
     * restriction captured by the {@link SplittableProcessElementInvoker}.
     *
     * <p>A hold is needed to avoid letting the output watermark immediately progress together with
     * the input watermark when the first {@link DoFn.ProcessElement} call for this element
     * completes.
     */
    private static final StateTag<WatermarkHoldState> watermarkHoldTag =
        StateTags.makeSystemTagInternal(
            StateTags.<GlobalWindow>watermarkStateInternal("hold", TimestampCombiner.LATEST));

    /**
     * The state cell containing a copy of the element. Written during the first {@link
     * DoFn.ProcessElement} call and read during subsequent calls in response to timer firings, when
     * the original element is no longer available.
     */
    private final StateTag<ValueState<WindowedValue<InputT>>> elementTag;

    /**
     * The state cell containing a restriction representing the unprocessed part of work for this
     * element.
     */
    private StateTag<ValueState<RestrictionT>> restrictionTag;

    private final DoFn<InputT, OutputT> fn;
    private final Coder<InputT> elementCoder;
    private final Coder<RestrictionT> restrictionCoder;
    private final WindowingStrategy<InputT, ?> inputWindowingStrategy;

    private transient StateInternalsFactory<String> stateInternalsFactory;
    private transient TimerInternalsFactory<String> timerInternalsFactory;
    private transient SplittableProcessElementInvoker<InputT, OutputT, RestrictionT, TrackerT>
        processElementInvoker;

    private transient DoFnInvoker<InputT, OutputT> invoker;

    public ProcessFn(
        DoFn<InputT, OutputT> fn,
        Coder<InputT> elementCoder,
        Coder<RestrictionT> restrictionCoder,
        WindowingStrategy<InputT, ?> inputWindowingStrategy) {
      this.fn = fn;
      this.elementCoder = elementCoder;
      this.restrictionCoder = restrictionCoder;
      this.inputWindowingStrategy = inputWindowingStrategy;
      this.elementTag =
          StateTags.value(
              "element",
              WindowedValue.getFullCoder(
                  elementCoder, inputWindowingStrategy.getWindowFn().windowCoder()));
      this.restrictionTag = StateTags.value("restriction", restrictionCoder);
    }

    public void setStateInternalsFactory(StateInternalsFactory<String> stateInternalsFactory) {
      this.stateInternalsFactory = stateInternalsFactory;
    }

    public void setTimerInternalsFactory(TimerInternalsFactory<String> timerInternalsFactory) {
      this.timerInternalsFactory = timerInternalsFactory;
    }

    public void setProcessElementInvoker(
        SplittableProcessElementInvoker<InputT, OutputT, RestrictionT, TrackerT> invoker) {
      this.processElementInvoker = invoker;
    }

    public DoFn<InputT, OutputT> getFn() {
      return fn;
    }

    public Coder<InputT> getElementCoder() {
      return elementCoder;
    }

    public Coder<RestrictionT> getRestrictionCoder() {
      return restrictionCoder;
    }

    public WindowingStrategy<InputT, ?> getInputWindowingStrategy() {
      return inputWindowingStrategy;
    }

    @Setup
    public void setup() throws Exception {
      invoker = DoFnInvokers.invokerFor(fn);
      invoker.invokeSetup();
    }

    @Teardown
    public void tearDown() throws Exception {
      invoker.invokeTeardown();
    }

    @StartBundle
    public void startBundle(StartBundleContext c) throws Exception {
      invoker.invokeStartBundle(wrapContextAsStartBundle(c));
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      invoker.invokeFinishBundle(wrapContextAsFinishBundle(c));
    }

    @ProcessElement
    public void processElement(final ProcessContext c) {
      String key = c.element().key();
      StateInternals stateInternals = stateInternalsFactory.stateInternalsForKey(key);
      TimerInternals timerInternals = timerInternalsFactory.timerInternalsForKey(key);

      // Initialize state (element and restriction) depending on whether this is the seed call.
      // The seed call is the first call for this element, which actually has the element.
      // Subsequent calls are timer firings and the element has to be retrieved from the state.
      TimerInternals.TimerData timer = Iterables.getOnlyElement(c.element().timersIterable(), null);
      boolean isSeedCall = (timer == null);
      StateNamespace stateNamespace;
      if (isSeedCall) {
        WindowedValue<ElementAndRestriction<InputT, RestrictionT>> windowedValue =
            Iterables.getOnlyElement(c.element().elementsIterable());
        BoundedWindow window = Iterables.getOnlyElement(windowedValue.getWindows());
        stateNamespace =
            StateNamespaces.window(
                (Coder<BoundedWindow>) inputWindowingStrategy.getWindowFn().windowCoder(), window);
      } else {
        stateNamespace = timer.getNamespace();
      }

      ValueState<WindowedValue<InputT>> elementState =
          stateInternals.state(stateNamespace, elementTag);
      ValueState<RestrictionT> restrictionState =
          stateInternals.state(stateNamespace, restrictionTag);
      WatermarkHoldState holdState = stateInternals.state(stateNamespace, watermarkHoldTag);

      ElementAndRestriction<WindowedValue<InputT>, RestrictionT> elementAndRestriction;
      if (isSeedCall) {
        WindowedValue<ElementAndRestriction<InputT, RestrictionT>> windowedValue =
            Iterables.getOnlyElement(c.element().elementsIterable());
        WindowedValue<InputT> element = windowedValue.withValue(windowedValue.getValue().element());
        elementState.write(element);
        elementAndRestriction =
            ElementAndRestriction.of(element, windowedValue.getValue().restriction());
      } else {
        // This is not the first ProcessElement call for this element/restriction - rather,
        // this is a timer firing, so we need to fetch the element and restriction from state.
        elementState.readLater();
        restrictionState.readLater();
        elementAndRestriction =
            ElementAndRestriction.of(elementState.read(), restrictionState.read());
      }

      final TrackerT tracker = invoker.invokeNewTracker(elementAndRestriction.restriction());
      SplittableProcessElementInvoker<InputT, OutputT, RestrictionT, TrackerT>.Result result =
          processElementInvoker.invokeProcessElement(
              invoker, elementAndRestriction.element(), tracker);

      // Save state for resuming.
      if (result.getResidualRestriction() == null) {
        // All work for this element/restriction is completed. Clear state and release hold.
        elementState.clear();
        restrictionState.clear();
        holdState.clear();
        return;
      }
      restrictionState.write(result.getResidualRestriction());
      Instant futureOutputWatermark = result.getFutureOutputWatermark();
      if (futureOutputWatermark == null) {
        futureOutputWatermark = elementAndRestriction.element().getTimestamp();
      }
      holdState.add(futureOutputWatermark);
      // Set a timer to continue processing this element.
      timerInternals.setTimer(
          TimerInternals.TimerData.of(
              stateNamespace, timerInternals.currentProcessingTime(), TimeDomain.PROCESSING_TIME));
    }

    private DoFn<InputT, OutputT>.StartBundleContext wrapContextAsStartBundle(
        final StartBundleContext baseContext) {
      return fn.new StartBundleContext() {
        @Override
        public PipelineOptions getPipelineOptions() {
          return baseContext.getPipelineOptions();
        }

        private void throwUnsupportedOutput() {
          throw new UnsupportedOperationException(
              String.format(
                  "Splittable DoFn can only output from @%s",
                  ProcessElement.class.getSimpleName()));
        }
      };
    }

    private DoFn<InputT, OutputT>.FinishBundleContext wrapContextAsFinishBundle(
        final FinishBundleContext baseContext) {
      return fn.new FinishBundleContext() {
        @Override
        public void output(OutputT output, Instant timestamp, BoundedWindow window) {
          throwUnsupportedOutput();
        }

        @Override
        public <T> void output(TupleTag<T> tag, T output, Instant timestamp, BoundedWindow window) {
          throwUnsupportedOutput();
        }

        @Override
        public PipelineOptions getPipelineOptions() {
          return baseContext.getPipelineOptions();
        }

        private void throwUnsupportedOutput() {
          throw new UnsupportedOperationException(
              String.format(
                  "Splittable DoFn can only output from @%s",
                  ProcessElement.class.getSimpleName()));
        }
      };
    }
  }
}
