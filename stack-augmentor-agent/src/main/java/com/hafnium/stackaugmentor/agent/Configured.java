package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.instrument.ParamSelection;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.advice.ExitAdviceFactory;
import com.hafnium.stackaugmentor.instrument.live.FrameSpecs;
import com.hafnium.stackaugmentor.instrument.live.LiveFrames;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.values.FrameFormat;
import com.hafnium.stackaugmentor.runtime.values.ValueResolver;
import net.bytebuddy.asm.Advice;

/**
 * What the agent makes of one configuration, the agent's own or that of a class loader: what it instruments, and how the
 * handler and the live-stack mode augment the frames of the classes that use it.
 */
public record Configured(ValueResolver resolver, FrameFormat format, ParamSelection parameters, TypeMatching matching, Advice advice,
                         FrameSpecs specs) implements LiveFrames.Context {

    public static Configured of(AugmentorConfig config) {
        return of(config, FrameFormat.create(config));
    }

    public static Configured of(AugmentorConfig config, FrameFormat format) {
        ParamSelection parameters = new ParamSelection(config);
        TypeMatching matching = new TypeMatching(config, parameters);
        return new Configured(new ValueResolver(config), format, parameters, matching, ExitAdviceFactory.create(matching, parameters),
                new FrameSpecs(config, matching));
    }
}
