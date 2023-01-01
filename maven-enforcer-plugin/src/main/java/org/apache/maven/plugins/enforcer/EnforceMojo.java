/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.enforcer;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerLevel;
import org.apache.maven.enforcer.rule.api.EnforcerLogger;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRule2;
import org.apache.maven.enforcer.rule.api.EnforcerRuleBase;
import org.apache.maven.enforcer.rule.api.EnforcerRuleError;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.enforcer.internal.EnforcerLoggerError;
import org.apache.maven.plugins.enforcer.internal.EnforcerLoggerWarn;
import org.apache.maven.plugins.enforcer.internal.EnforcerRuleCache;
import org.apache.maven.plugins.enforcer.internal.EnforcerRuleDesc;
import org.apache.maven.plugins.enforcer.internal.EnforcerRuleManager;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/**
 * This goal executes the defined enforcer-rules once per module.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
@Mojo(
        name = "enforce",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyCollection = ResolutionScope.TEST,
        threadSafe = true)
public class EnforceMojo extends AbstractMojo {
    /**
     * This is a static variable used to persist the cached results across plugin invocations.
     */
    protected static Hashtable<String, EnforcerRule> cache = new Hashtable<>();

    /**
     * MojoExecution needed by the ExpressionEvaluator
     */
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    protected MojoExecution mojoExecution;

    /**
     * The MavenSession
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /**
     * POM
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * Flag to easily skip all checks
     */
    @Parameter(property = "enforcer.skip", defaultValue = "false")
    protected boolean skip = false;

    /**
     * Flag to fail the build if at least one check fails.
     */
    @Parameter(property = "enforcer.fail", defaultValue = "true")
    private boolean fail = true;

    /**
     * Fail on the first rule that doesn't pass
     */
    @Parameter(property = "enforcer.failFast", defaultValue = "false")
    private boolean failFast = false;

    /**
     * Flag to fail the build if no rules are present
     *
     * @since 3.2.0
     */
    @Parameter(property = "enforcer.failIfNoRules", defaultValue = "true")
    private boolean failIfNoRules = true;

    /**
     * Rules configuration to execute as XML.
     * Each first level tag represents rule name to execute.
     * Inner tags are configurations for rule.
     * Eg:
     * <pre>
     *     &lt;rules&gt;
     *         &lt;alwaysFail/&gt;
     *         &lt;alwaysPass&gt;
     *             &lt;message&gt;message for rule&lt;/message&gt;
     *         &lt;/alwaysPass&gt;
     *         &lt;myRule implementation="org.example.MyRule"/&gt;
     *     &lt;/rules&gt;
     * </pre>
     *
     * @since 1.0.0
     */
    @Parameter
    private PlexusConfiguration rules;

    /**
     * List of strings that matches the EnforcerRules to skip.
     *
     * @since 3.2.0
     */
    @Parameter(required = false, property = "enforcer.skipRules")
    private List<String> rulesToSkip;

    /**
     * Use this flag to disable rule result caching. This will cause all rules to execute on each project even if the
     * rule indicates it can safely be cached.
     */
    @Parameter(property = "enforcer.ignoreCache", defaultValue = "false")
    protected boolean ignoreCache = false;

    @Component
    private PlexusContainer container;

    @Component
    private EnforcerRuleManager enforcerRuleManager;

    @Component
    private EnforcerRuleCache ruleCache;

    private List<String> rulesToExecute;

    /**
     * List of strings that matches the EnforcerRules to execute. Replacement for the <code>rules</code> property.
     *
     * @since 3.2.0
     */
    @Parameter(required = false, property = "enforcer.rules")
    public void setRulesToExecute(List<String> rulesToExecute) throws MojoExecutionException {
        if (rulesToExecute != null && !rulesToExecute.isEmpty()) {
            if (this.rulesToExecute != null && !this.rulesToExecute.isEmpty()) {
                throw new MojoExecutionException("Detected the usage of both '-Drules' (which is deprecated) "
                        + "and '-Denforcer.rules'. Please use only one of them, preferably '-Denforcer.rules'.");
            }
            this.rulesToExecute = rulesToExecute;
        }
    }

    /**
     * List of strings that matches the EnforcerRules to execute.
     *
     * @deprecated Use <code>enforcer.rules</code> property instead
     */
    @Parameter(required = false, property = "rules")
    @Deprecated
    public void setCommandLineRules(List<String> rulesToExecute) throws MojoExecutionException {
        if (rulesToExecute != null && !rulesToExecute.isEmpty()) {
            getLog().warn(
                            "Detected the usage of property '-Drules' which is deprecated. Use '-Denforcer.rules' instead.");
        }
        setRulesToExecute(rulesToExecute);
    }

    private EnforcerLogger enforcerLoggerError;

    private EnforcerLogger enforcerLoggerWarn;

    @Override
    public void execute() throws MojoExecutionException {
        Log log = this.getLog();

        if (isSkip()) {
            log.info("Skipping Rule Enforcement.");
            return;
        }

        Optional<PlexusConfiguration> rulesFromCommandLine = createRulesFromCommandLineOptions();
        List<EnforcerRuleDesc> rulesList;
        try {
            // current behavior - rules from command line override all other configured rules.
            List<EnforcerRuleDesc> allRules = enforcerRuleManager.createRules(rulesFromCommandLine.orElse(rules));
            rulesList = filterOutSkippedRules(allRules);
        } catch (EnforcerRuleManagerException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        if (rulesList.isEmpty()) {
            if (isFailIfNoRules()) {
                throw new MojoExecutionException(
                        "No rules are configured. Use the skip flag if you want to disable execution.");
            } else {
                log.warn("No rules are configured.");
                return;
            }
        }

        enforcerLoggerError = new EnforcerLoggerError(log);
        enforcerLoggerWarn = new EnforcerLoggerWarn(log);

        // messages with warn/error flag
        Map<String, Boolean> messages = new LinkedHashMap<>();

        // create my helper
        PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, mojoExecution);
        EnforcerRuleHelper helper = new DefaultEnforcementRuleHelper(session, evaluator, log, container);

        // if we are only warning, then disable
        // failFast
        if (!fail) {
            failFast = false;
        }

        boolean hasErrors = false;

        // go through each rule
        for (int ruleIndex = 0; ruleIndex < rulesList.size(); ruleIndex++) {

            // prevent against empty rules
            EnforcerRuleDesc ruleDesc = rulesList.get(ruleIndex);
            if (ruleDesc != null) {
                EnforcerRuleBase rule = ruleDesc.getRule();
                EnforcerLevel level = getLevel(rule);
                try {
                    executeRule(ruleIndex, ruleDesc, helper);
                } catch (EnforcerRuleError e) {
                    String ruleMessage = createRuleMessage(ruleIndex, ruleDesc, EnforcerLevel.ERROR, e);
                    throw new MojoExecutionException(ruleMessage, e);
                } catch (EnforcerRuleException e) {

                    String ruleMessage = createRuleMessage(ruleIndex, ruleDesc, level, e);

                    if (failFast && level == EnforcerLevel.ERROR) {
                        throw new MojoExecutionException(ruleMessage, e);
                    }

                    if (level == EnforcerLevel.ERROR) {
                        hasErrors = true;
                        messages.put(ruleMessage, true);
                    } else {
                        messages.put(ruleMessage, false);
                    }
                }
            }
        }

        // log any messages
        messages.forEach((message, error) -> {
            if (fail && error) {
                log.error(message);
            } else {
                log.warn(message);
            }
        });

        if (fail && hasErrors) {
            throw new MojoExecutionException(
                    "Some Enforcer rules have failed. Look above for specific messages explaining why the rule failed.");
        }
    }

    private void executeRule(int ruleIndex, EnforcerRuleDesc ruleDesc, EnforcerRuleHelper helper)
            throws EnforcerRuleException {

        if (getLog().isDebugEnabled()) {
            getLog().debug(String.format("Executing Rule %d: %s", ruleIndex, ruleDesc.getRule()));
        }

        long startTime = System.currentTimeMillis();

        try {
            if (ruleDesc.getRule() instanceof EnforcerRule) {
                executeRuleOld(ruleIndex, ruleDesc, helper);
            } else if (ruleDesc.getRule() instanceof AbstractEnforcerRule) {
                executeRuleNew(ruleIndex, ruleDesc);
            }
        } finally {
            if (getLog().isDebugEnabled()) {
                long workTime = System.currentTimeMillis() - startTime;
                getLog().debug(String.format(
                        "Finish Rule %d: %s take %d ms", ruleIndex, getRuleName(ruleDesc), workTime));
            }
        }
    }

    private void executeRuleOld(int ruleIndex, EnforcerRuleDesc ruleDesc, EnforcerRuleHelper helper)
            throws EnforcerRuleException {

        EnforcerRule rule = (EnforcerRule) ruleDesc.getRule();

        if (ignoreCache || shouldExecute(rule)) {
            rule.execute(helper);
            getLog().info(String.format("Rule %d: %s executed", ruleIndex, getRuleName(ruleDesc)));
        }
    }

    private void executeRuleNew(int ruleIndex, EnforcerRuleDesc ruleDesc) throws EnforcerRuleException {

        AbstractEnforcerRule rule = (AbstractEnforcerRule) ruleDesc.getRule();
        rule.setLog(rule.getLevel() == EnforcerLevel.ERROR ? enforcerLoggerError : enforcerLoggerWarn);

        if (ignoreCache || !ruleCache.isCached(rule)) {
            rule.execute();
            getLog().info(String.format("Rule %d: %s executed", ruleIndex, getRuleName(ruleDesc)));
        }
    }

    /**
     * Create rules configuration based on command line provided rules list.
     *
     * @return an configuration in case where rules list is present or empty
     */
    private Optional<PlexusConfiguration> createRulesFromCommandLineOptions() {

        if (rulesToExecute == null || rulesToExecute.isEmpty()) {
            return Optional.empty();
        }

        PlexusConfiguration configuration = new DefaultPlexusConfiguration("rules");
        for (String rule : rulesToExecute) {
            configuration.addChild(new DefaultPlexusConfiguration(rule));
        }
        return Optional.of(configuration);
    }

    /**
     * Filter out (remove) rules that have been specifically skipped via additional configuration.
     *
     * @param allRules list of enforcer rules to go through and filter
     * @return list of filtered rules
     */
    private List<EnforcerRuleDesc> filterOutSkippedRules(List<EnforcerRuleDesc> allRules) {
        if (rulesToSkip == null || rulesToSkip.isEmpty()) {
            return allRules;
        }
        return allRules.stream()
                .filter(ruleDesc -> !rulesToSkip.contains(ruleDesc.getName()))
                .collect(Collectors.toList());
    }

    /**
     * This method determines if a rule should execute based on the cache
     *
     * @param rule the rule to verify
     * @return {@code true} if rule should be executed, otherwise {@code false}
     */
    protected boolean shouldExecute(EnforcerRule rule) {
        if (rule.isCacheable()) {
            Log log = this.getLog();
            log.debug("Rule " + rule.getClass().getName() + " is cacheable.");
            String key = rule.getClass().getName() + " " + rule.getCacheId();
            if (EnforceMojo.cache.containsKey(key)) {
                log.debug("Key " + key + " was found in the cache");
                if (rule.isResultValid(cache.get(key))) {
                    log.debug("The cached results are still valid. Skipping the rule: "
                            + rule.getClass().getName());
                    return false;
                }
            }

            // add it to the cache of executed rules
            EnforceMojo.cache.put(key, rule);
        }
        return true;
    }

    /**
     * @return the fail
     */
    public boolean isFail() {
        return this.fail;
    }

    /**
     * @param theFail the fail to set
     */
    public void setFail(boolean theFail) {
        this.fail = theFail;
    }

    /**
     * @param theFailFast the failFast to set
     */
    public void setFailFast(boolean theFailFast) {
        this.failFast = theFailFast;
    }

    public boolean isFailFast() {
        return failFast;
    }

    private String createRuleMessage(
            int ruleIndex, EnforcerRuleDesc ruleDesc, EnforcerLevel level, EnforcerRuleException e) {

        StringBuilder result = new StringBuilder();
        result.append("Rule ").append(ruleIndex).append(": ").append(getRuleName(ruleDesc));

        if (level == EnforcerLevel.ERROR) {
            result.append(" failed");
        } else {
            result.append(" warned");
        }

        if (e.getMessage() != null) {
            result.append(" with message:").append(System.lineSeparator()).append(e.getMessage());
        } else {
            result.append(" without a message");
        }

        return result.toString();
    }

    private String getRuleName(EnforcerRuleDesc ruleDesc) {

        Class<? extends EnforcerRuleBase> ruleClass = ruleDesc.getRule().getClass();

        String ruleName = ruleClass.getName();

        if (!ruleClass.getSimpleName().equalsIgnoreCase(ruleDesc.getName())) {
            ruleName += "(" + ruleDesc.getName() + ")";
        }

        return ruleName;
    }

    /**
     * Returns the level of the rule, defaults to {@link EnforcerLevel#ERROR} for backwards compatibility.
     *
     * @param rule might be of type {{@link AbstractEnforcerRule} or {@link EnforcerRule2}
     * @return level of the rule.
     */
    private EnforcerLevel getLevel(EnforcerRuleBase rule) {
        if (rule instanceof AbstractEnforcerRule) {
            return ((AbstractEnforcerRule) rule).getLevel();
        } else if (rule instanceof EnforcerRule2) {
            return ((EnforcerRule2) rule).getLevel();
        } else {
            return EnforcerLevel.ERROR;
        }
    }

    /**
     * @return the skip
     */
    public boolean isSkip() {
        return this.skip;
    }

    /**
     * @param theSkip the skip to set
     */
    public void setSkip(boolean theSkip) {
        this.skip = theSkip;
    }

    /**
     * @return the failIfNoRules
     */
    public boolean isFailIfNoRules() {
        return this.failIfNoRules;
    }

    /**
     * @param thefailIfNoRules the failIfNoRules to set
     */
    public void setFailIfNoRules(boolean thefailIfNoRules) {
        this.failIfNoRules = thefailIfNoRules;
    }

    /**
     * @return the project
     */
    public MavenProject getProject() {
        return this.project;
    }

    /**
     * @param theProject the project to set
     */
    public void setProject(MavenProject theProject) {
        this.project = theProject;
    }

    /**
     * @return the session
     */
    public MavenSession getSession() {
        return this.session;
    }

    /**
     * @param theSession the session to set
     */
    public void setSession(MavenSession theSession) {
        this.session = theSession;
    }
}
