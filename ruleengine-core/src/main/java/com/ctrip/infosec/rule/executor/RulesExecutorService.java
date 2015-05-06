/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ctrip.infosec.rule.executor;

import com.ctrip.infosec.common.model.RiskFact;
import com.ctrip.infosec.configs.Configs;
import com.ctrip.infosec.configs.event.Rule;
import com.ctrip.infosec.configs.utils.BeanMapper;
import com.ctrip.infosec.common.Constants;
import com.ctrip.infosec.rule.Contexts;
import com.ctrip.infosec.rule.engine.StatelessRuleEngine;
import com.ctrip.infosec.sars.monitor.SarsMonitorContext;
import com.ctrip.infosec.sars.util.SpringContextHolder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 使用线程池并行执行规则
 *
 * @author zhengby
 */
@Service
public class RulesExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(RulesExecutorService.class);
    private ThreadPoolExecutor excutor = new ThreadPoolExecutor(64, 512, 60, TimeUnit.SECONDS, new SynchronousQueue(), new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 执行同步规则
     */
    public RiskFact executeSyncRules(RiskFact fact) {
        logger.info(Contexts.getLogPrefix() + "execute sync rules ...");
        if (fact.results == null) {
            fact.setResults(new HashMap<String, Map<String, Object>>());
        }
        if (fact.ext == null) {
            fact.setExt(new HashMap<String, Object>());
        }
        executeParallel(fact, false); //execute(fact, false);

        // 返回结果
        Map<String, Object> finalResult = Constants.defaultResult;
        for (Map<String, Object> rs : fact.results.values()) {
            finalResult = compareAndReturn(finalResult, rs);
        }
        for (Map<String, Object> rs : fact.finalResultGroupByScene.values()) {
            finalResult = compareAndReturn(finalResult, rs);
        }
        fact.setFinalResult(
                ImmutableMap.of(
                        Constants.riskLevel, finalResult.get(Constants.riskLevel),
                        Constants.riskMessage, finalResult.get(Constants.riskMessage)
                ));
        logger.info(Contexts.getLogPrefix() + "execute sync rules finished. finalResult: riskLevel="
                + finalResult.get(Constants.riskLevel) + ", riskMessage=" + finalResult.get(Constants.riskMessage));

        return fact;
    }

    /**
     * 执行异步规则
     */
    public RiskFact executeAsyncRules(RiskFact fact) {
        logger.info(Contexts.getLogPrefix() + "execute async rules ...");
        if (fact.results == null) {
            fact.setResults(new HashMap<String, Map<String, Object>>());
        }
        if (fact.ext == null) {
            fact.setExt(new HashMap<String, Object>());
        }
        execute(fact, true);

        // 返回结果
        Map<String, Object> finalResult = Constants.defaultResult;
        for (Map<String, Object> rs : fact.results.values()) {
            finalResult = compareAndReturn(finalResult, rs);
        }
        for (Map<String, Object> rs : fact.finalResultGroupByScene.values()) {
            finalResult = compareAndReturn(finalResult, rs);
        }
        fact.setFinalResult(
                ImmutableMap.of(
                        Constants.riskLevel, finalResult.get(Constants.riskLevel),
                        Constants.riskMessage, finalResult.get(Constants.riskMessage)
                ));
        logger.info(Contexts.getLogPrefix() + "execute async rules finished. finalResult: riskLevel="
                + finalResult.get(Constants.riskLevel) + ", riskMessage=" + finalResult.get(Constants.riskMessage));
        return fact;
    }

    /**
     * 串行执行
     */
    void execute(RiskFact fact, boolean isAsync) {

        // matchRules      
        List<Rule> matchedRules = Configs.matchRules(fact, isAsync);
        logger.info(Contexts.getLogPrefix() + "matched rules: " + matchedRules.size());
        StatelessRuleEngine statelessRuleEngine = SpringContextHolder.getBean(StatelessRuleEngine.class);

        StopWatch clock = new StopWatch();
        for (Rule rule : matchedRules) {
            String packageName = rule.getRuleNo();
            try {
                clock.reset();
                clock.start();

                // set default result
                Map<String, Object> defaultResult = Maps.newHashMap();
                defaultResult.put(Constants.riskLevel, 0);
                defaultResult.put(Constants.riskMessage, "PASS");
                fact.results.put(rule.getRuleNo(), defaultResult);

                // add current execute ruleNo and logPrefix before execution
                fact.ext.put(Constants.key_ruleNo, rule.getRuleNo());
                fact.ext.put(Constants.key_logPrefix, SarsMonitorContext.getLogPrefix());

                statelessRuleEngine.execute(packageName, fact);

                // remove current execute ruleNo when finished execution.
                fact.ext.remove(Constants.key_ruleNo);
                fact.ext.remove(Constants.key_logPrefix);

                clock.stop();
                long handlingTime = clock.getTime();

                Map<String, Object> result = fact.results.get(packageName);
                result.put(Constants.async, isAsync);
                result.put(Constants.timeUsage, handlingTime);
                logger.info(Contexts.getLogPrefix() + "rule: " + packageName + ", riskLevel: " + result.get(Constants.riskLevel)
                        + ", riskMessage: " + result.get(Constants.riskMessage) + ", usage: " + result.get(Constants.timeUsage) + "ms");

            } catch (Throwable ex) {
                logger.warn(Contexts.getLogPrefix() + "invoke stateless rule failed. packageName: " + packageName, ex);
            }
        }
    }

    /**
     * 并行执行
     */
    void executeParallel(RiskFact fact, boolean isAsync) {

        // matchRules        
        List<Rule> matchedRules = Configs.matchRules(fact, isAsync);
        logger.info(Contexts.getLogPrefix() + "matched rules: " + matchedRules.size());
        List<Callable<RuleExecuteResultWithEvent>> runs = Lists.newArrayList();
        for (Rule rule : matchedRules) {
            final RiskFact factCopy = BeanMapper.copy(fact, RiskFact.class);

            // set default result
            Map<String, Object> defaultResult = Maps.newHashMap();
            defaultResult.put(Constants.riskLevel, 0);
            defaultResult.put(Constants.riskMessage, "PASS");
            factCopy.results.put(rule.getRuleNo(), defaultResult);

            final StatelessRuleEngine statelessRuleEngine = SpringContextHolder.getBean(StatelessRuleEngine.class);
            final String packageName = rule.getRuleNo();
            final String logPrefix = Contexts.getLogPrefix();
            final boolean async = isAsync;
            try {
                //add current execute ruleNo before execution
                factCopy.ext.put(Constants.key_ruleNo, rule.getRuleNo());
                factCopy.ext.put(Constants.key_logPrefix, SarsMonitorContext.getLogPrefix());

                runs.add(new Callable<RuleExecuteResultWithEvent>() {

                    @Override
                    public RuleExecuteResultWithEvent call() throws Exception {
                        try {
                            long start = System.currentTimeMillis();
                            //remove current execute ruleNo when finished execution.
                            statelessRuleEngine.execute(packageName, factCopy);
                            factCopy.ext.remove(Constants.key_ruleNo);
                            Map<String, Object> result = factCopy.results.get(packageName);
                            result.put(Constants.async, async);
                            result.put(Constants.timeUsage, System.currentTimeMillis() - start);
                            logger.info(logPrefix + "rule: " + packageName + ", riskLevel: " + result.get(Constants.riskLevel)
                                    + ", riskMessage: " + result.get(Constants.riskMessage) + ", usage: " + result.get(Constants.timeUsage) + "ms");
                            return new RuleExecuteResultWithEvent(packageName, result, factCopy.finalResultGroupByScene, factCopy.eventBody);
                        } catch (Exception e) {
                            logger.warn(logPrefix + "invoke stateless rule failed. packageName: " + packageName, e);
                        }
                        return null;
                    }

                });

            } catch (Throwable ex) {
                logger.warn(logPrefix + "invoke stateless rule failed. packageName: " + packageName, ex);
            }

        }
        List<RuleExecuteResultWithEvent> rawResult = new ArrayList<RuleExecuteResultWithEvent>();
        try {
            List<Future<RuleExecuteResultWithEvent>> result = excutor.invokeAll(runs, 2L, TimeUnit.SECONDS);
            for (Future f : result) {
                try {
                    if (f.isDone()) {
                        RuleExecuteResultWithEvent r = (RuleExecuteResultWithEvent) f.get();
                        rawResult.add(r);
                    } else {
                        f.cancel(true);
                    }
                } catch (Exception e) {

                }
            }
        } catch (Exception e) {

        }
        if (rawResult.size() > 0) {
            for (RuleExecuteResultWithEvent item : rawResult) {
                // merge eventBody
                if (item.getEventBody() != null) {
                    for (String key : item.getEventBody().keySet()) {
                        if (!fact.eventBody.containsKey(key)) {
                            fact.eventBody.put(key, item.getEventBody().get(key));
                        }
                    }
                }
                // merge results
                if (item.getResult() != null) {
                    fact.results.put(item.ruleNo, item.getResult());
                }
                // merge finalResultGroupByScene
                if (item.getResultGroupByScene() != null) {
                    for (String r : item.getResultGroupByScene().keySet()) {
                        Map<String, Object> rs = item.getResultGroupByScene().get(r);
                        if (rs != null) {
                            Map<String, Object> rsInFact = fact.finalResultGroupByScene.get(r);
                            if (rsInFact != null) {
                                int riskLevel = MapUtils.getIntValue(rs, Constants.riskLevel, 0);
                                int riskLevelInFact = MapUtils.getIntValue(rsInFact, Constants.riskLevel, 0);
                                if (riskLevel > riskLevelInFact) {
                                    fact.finalResultGroupByScene.put(r, rs);
                                }
                            } else {
                                fact.finalResultGroupByScene.put(r, rs);
                            }
                        }
                    }
                }
            }
        }
    }

    class RuleExecuteResultWithEvent {

        private String ruleNo;
        private Map<String, Object> result;
        private Map<String, Map<String, Object>> resultGroupByScene;
        private Map<String, Object> eventBody;

        public RuleExecuteResultWithEvent(String ruleNo, Map<String, Object> result, Map<String, Map<String, Object>> resultGroupByScene, Map<String, Object> eventBody) {
            this.ruleNo = ruleNo;
            this.result = result;
            this.resultGroupByScene = resultGroupByScene;
            this.eventBody = eventBody;
        }

        public String getRuleNo() {
            return ruleNo;
        }

        public void setRuleNo(String ruleNo) {
            this.ruleNo = ruleNo;
        }

        public Map<String, Object> getResult() {
            return result;
        }

        public void setResult(Map<String, Object> result) {
            this.result = result;
        }

        public Map<String, Map<String, Object>> getResultGroupByScene() {
            return resultGroupByScene;
        }

        public void setResultGroupByScene(Map<String, Map<String, Object>> resultGroupByScene) {
            this.resultGroupByScene = resultGroupByScene;
        }

        public Map<String, Object> getEventBody() {
            return eventBody;
        }

        public void setEventBody(Map<String, Object> eventBody) {
            this.eventBody = eventBody;
        }

    }

    /**
     * 返回分值高的结果作为finalResult
     */
    Map<String, Object> compareAndReturn(Map<String, Object> oldResult, Map<String, Object> newResult) {
        if (newResult == null) {
            return oldResult;
        }
        if (oldResult == null) {
            return newResult;
        }
        int newRriskLevel = MapUtils.getInteger(newResult, Constants.riskLevel, 0);
        int oldRriskLevel = MapUtils.getInteger(oldResult, Constants.riskLevel, 0);
        if (newRriskLevel > oldRriskLevel) {
            return newResult;
        }
        return oldResult;
    }
}
