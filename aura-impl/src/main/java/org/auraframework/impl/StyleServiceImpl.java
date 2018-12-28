/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.impl;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.auraframework.css.ResolveStrategy.PASSTHROUGH;
import static org.auraframework.css.ResolveStrategy.RESOLVE_NORMAL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.auraframework.adapter.StyleAdapter;
import org.auraframework.annotations.Annotations.ServiceComponent;
import org.auraframework.css.StyleContext;
import org.auraframework.css.TokenValueProvider;
import org.auraframework.def.BaseComponentDef;
import org.auraframework.def.BaseStyleDef;
import org.auraframework.def.DefDescriptor;
import org.auraframework.def.DefDescriptor.DefType;
import org.auraframework.def.FlavoredStyleDef;
import org.auraframework.def.StyleDef;
import org.auraframework.def.TokensDef;
import org.auraframework.impl.css.parser.CssPreprocessor;
import org.auraframework.impl.css.parser.plugin.TokenExpression;
import org.auraframework.impl.css.parser.plugin.TokenFunction;
import org.auraframework.impl.css.token.StyleContextImpl;
import org.auraframework.service.ContextService;
import org.auraframework.service.DefinitionService;
import org.auraframework.service.StyleService;
import org.auraframework.system.AuraContext;
import org.auraframework.throwable.quickfix.QuickFixException;
import org.auraframework.util.AuraTextUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.salesforce.omakase.ast.CssAnnotation;
import com.salesforce.omakase.ast.atrule.AtRule;
import com.salesforce.omakase.ast.declaration.Declaration;
import com.salesforce.omakase.broadcast.annotation.Rework;
import com.salesforce.omakase.plugin.Plugin;
import com.salesforce.omakase.plugin.conditionals.ConditionalsValidator;
import com.salesforce.omakase.plugin.syntax.UnquotedIEFilterPlugin;
import com.salesforce.omakase.util.Args;

@ServiceComponent
public class StyleServiceImpl implements StyleService {
    @Inject
    private StyleAdapter styleAdapter;

    @Inject
    private ContextService contextService;

    @Inject
    private DefinitionService definitionService;

    @Override
    public String applyTokens(DefDescriptor<TokensDef> tokens) throws QuickFixException {
        checkNotNull(tokens, "the 'tokens' param cannot be null");
        DefDescriptor<? extends BaseComponentDef> appDescriptor = contextService.getCurrentContext().getLoadingApplicationDescriptor();
        if (appDescriptor == null) {
            throw new IllegalStateException("application descriptor not set in aura context");
        }

        Set<DefDescriptor<? extends BaseStyleDef>> styleDescriptors = getStyleDependencies(appDescriptor);
        String styles = extractStyles(ImmutableList.of(tokens), styleDescriptors, true);
        return styles;
    }

    @Override
    public String applyTokens(DefDescriptor<TokensDef> tokens, DefDescriptor<? extends BaseStyleDef> style) throws QuickFixException {
        return applyTokens(ImmutableList.of(tokens), ImmutableList.of(style));
    }

    @Override
    public String applyTokens(DefDescriptor<TokensDef> tokens, Iterable<DefDescriptor<? extends BaseStyleDef>> styles) throws QuickFixException {
        return applyTokens(ImmutableList.of(tokens), styles);
    }

    @Override
    public String applyTokens(Iterable<DefDescriptor<TokensDef>> tokens, Iterable<DefDescriptor<? extends BaseStyleDef>> styles)
            throws QuickFixException {
        checkNotNull(tokens, "the 'tokens' arg cannot be null");
        checkNotNull(tokens, "the 'styles' arg cannot be null");
        return extractStyles(tokens, styles, false);
    }

    @Override
    public String applyTokensContextual(DefDescriptor<TokensDef> tokens, Iterable<DefDescriptor<? extends BaseStyleDef>> extraStyles)
            throws QuickFixException {
        return applyTokensContextual(ImmutableList.of(tokens), extraStyles);
    }

    @Override
    public String applyTokensContextual(Iterable<DefDescriptor<TokensDef>> tokens, Iterable<DefDescriptor<? extends BaseStyleDef>> extraStyles)
            throws QuickFixException {
        AuraContext context = contextService.getCurrentContext();
        Set<DefDescriptor<? extends BaseStyleDef>> clientLoaded = new LinkedHashSet<>();

        // attempt to automatically detect client-loaded styles
        for (DefDescriptor<?> desc : context.getClientLoaded().keySet()) {
            // include inner deps
            clientLoaded.addAll(getStyleDependencies(desc));

            // add the client loaded style def itself, (purposely done after!)
            DefDescriptor<StyleDef> style = definitionService.getDefDescriptor(desc, DefDescriptor.CSS_PREFIX, StyleDef.class);
            if (style.exists()) {
                clientLoaded.add(style);
            }
            DefDescriptor<FlavoredStyleDef> flavor = definitionService.getDefDescriptor(desc, DefDescriptor.CSS_PREFIX, FlavoredStyleDef.class);
            if (flavor.exists()) {
                clientLoaded.add(flavor);
            }
        }

        Set<DefDescriptor<? extends BaseStyleDef>> styles = new LinkedHashSet<>(256);

        // add style def descriptors based on app dependencies
        String uid = context.getUid(context.getLoadingApplicationDescriptor());
        for (DefDescriptor<?> dependency : definitionService.getDependencies(uid)) {
            if (dependency.getDefType() == DefType.STYLE || dependency.getDefType() == DefType.FLAVORED_STYLE) {
                @SuppressWarnings("unchecked")
                DefDescriptor<? extends BaseStyleDef> desc = ((DefDescriptor<? extends BaseStyleDef>)dependency);
                styles.add(desc);
            }
        }

        // add clientLoaded styles
        Iterables.addAll(styles, clientLoaded);

        // add extra styles
        if (extraStyles != null) {
            Iterables.addAll(styles, extraStyles);
        }

        return extractStyles(tokens, styles, false);
    }

    /** gets all style dependencies for the given descriptor */
    private Set<DefDescriptor<? extends BaseStyleDef>> getStyleDependencies(DefDescriptor<?> descriptor) throws QuickFixException {
        Set<DefDescriptor<? extends BaseStyleDef>> styles = new LinkedHashSet<>();

        String uid = definitionService.getUid(null, descriptor);
        if (uid != null) {
            Set<DefDescriptor<?>> dependencies = definitionService.getDependencies(uid);
            if (dependencies != null) {
                for (DefDescriptor<?> dep : dependencies) {
                    if (BaseStyleDef.class.isAssignableFrom(dep.getDefType().getPrimaryInterface())) {
                        @SuppressWarnings("unchecked") // did primary interface check above
                        DefDescriptor<? extends BaseStyleDef> desc = (DefDescriptor<? extends BaseStyleDef>) dep;
                        styles.add(desc);
                    }
                }
            }
        }

        return styles;
    }

    /** here's the good stuff */
    private String extractStyles(Iterable<DefDescriptor<TokensDef>> tokens, Iterable<DefDescriptor<? extends BaseStyleDef>> styles, boolean strictFilter)
            throws QuickFixException {

        AuraContext context = contextService.getCurrentContext();
        StyleContext styleContext = StyleContextImpl.build(definitionService, context, tokens);

        // figure out which tokens we will be utilizing
        Set<String> tokenNames = styleContext.getTokens().getNames(tokens);

        // pre-filter style defs
        // 1: skip over any styles without expressions
        // 2: skip any styles not using a relevant token
        List<BaseStyleDef> filtered = new ArrayList<>();
        for (DefDescriptor<? extends BaseStyleDef> style : styles) {
            BaseStyleDef def = definitionService.getDefinition(style);

            if (strictFilter) {
                Set<String> defTokenNames = def.getTokenNames();
                if (!defTokenNames.isEmpty() && defTokenNames.stream().anyMatch(tokenNames::contains)) {
                    filtered.add(def);
                }
            } else if (!def.getExpressions().isEmpty()) { // preserve option for the original behavior for 212, remove this path in 214+
                filtered.add(def);
            }
        }

        // process css
        StringBuilder out = new StringBuilder(512);
        ConditionalsValidator conditionalsValidator = new ConditionalsValidator();

        for (BaseStyleDef style : filtered) {
            MagicEraser magicEraser = new MagicEraser(tokenNames, style.getDescriptor());
            TokenValueProvider tvp = styleAdapter.getTokenValueProvider(style.getDescriptor(), PASSTHROUGH, styleContext.getTokens());

            // first pass reduces the css to the stuff that utilizes a relevant var.
            // we run this in a separate pass so that our MagicEraser plugin gets TokenFunctions delivered
            // with the args unevaluated (otherwise it will just be replaced with the value). In other words,
            // so that we can use the token plugin in passthrough mode.
            String css = CssPreprocessor.raw()
                    .source(style.getRawCode())
                    .tokens(style.getDescriptor(), tvp)
                    .extra(new UnquotedIEFilterPlugin())
                    .extra(conditionalsValidator)
                    .extra(magicEraser)
                    .parse()
                    .content();

            tvp = styleAdapter.getTokenValueProvider(style.getDescriptor(), RESOLVE_NORMAL, styleContext.getTokens());

            // second pass evaluates as normal (applies token function values, conditionals, etc...)
            List<Plugin> contextual = styleAdapter.getContextualRuntimePlugins();
            css = CssPreprocessor.runtime(styleContext, styleAdapter)
                    .source(css)
                    .tokens(style.getDescriptor(), tvp)
                    .extras(contextual)
                    .parse()
                    .content();

            if (!AuraTextUtil.isEmptyOrWhitespace(css)) {
                // in dev mode, output a comment indicating which style def this css came from
                if (context.isDevMode()) {
                    out.append(String.format("/* %s */\n", style.getDescriptor()));
                }
                out.append(css);
                if (context.isDevMode()) {
                    out.append("\n");
                }
            }
        }

        return out.toString();
    }

    /**
     * CSS plugin that eliminates unnecessary CSS.
     * <p>
     * Any declaration not using a relevant token is removed. Any at-rule that doesn't contain a declaration using a
     * relevant token is removed, except for media queries using a relevant token in the expression. In the case of the
     * latter, the entire block of the media query is then included irrespective of token usage.
     */
    private final class MagicEraser implements Plugin {
        private final CssAnnotation ANNOTATION = new CssAnnotation("keep");
        private final Set<String> tokens;
        private final TokenValueProvider tvp;

        public MagicEraser(Set<String> tokens, DefDescriptor<? extends BaseStyleDef> style) {
            this.tokens = tokens;
            this.tvp = styleAdapter.getTokenValueProvider(style);
        }

        /** determines if the given string contains reference to one of the specified tokens */
        private boolean hasMatchingToken(String expression) throws QuickFixException {
            return !Collections.disjoint(tokens, tvp.extractTokenNames(expression, true));
        }

        /** if the function references one of our tokens then add an annotation indicating we should keep it */
        @Rework
        public void annotate(TokenFunction function) throws QuickFixException {
            Declaration declaration = function.declaration();
            if (hasMatchingToken(function.args())) {
                declaration.annotate(ANNOTATION);

                // add annotation to at-rule if we are inside of one as well
                Optional<AtRule> atRule = declaration.parentAtRule();
                if (atRule.isPresent()) {
                    atRule.get().annotateUnlessPresent(ANNOTATION);
                }
            }
        }

        /** if the media query references on of our tokens then annotate it */
        @Rework
        public void annotate(TokenExpression expression) throws QuickFixException {
            if (hasMatchingToken(Args.extract(expression.expression()))) {
                expression.parent().annotateUnlessPresent(ANNOTATION);
            }
        }

        /** remove all declarations that weren't given the special annotation */
        @Rework
        public void sift(Declaration declaration) {
            if (!declaration.hasAnnotation(ANNOTATION)) {
                // all declarations in retained at-rules should be kept as well
                Optional<AtRule> atRule = declaration.parentAtRule();
                if (!atRule.isPresent() || !atRule.get().hasAnnotation(ANNOTATION)) {
                    declaration.destroy();
                }
            }
        }

        /** remove all at-rules that weren't given the special annotation */
        @Rework
        public void sift(AtRule atRule) {
            if (!atRule.hasAnnotation(ANNOTATION)) {
                atRule.destroy();
            }
        }
    }
}
