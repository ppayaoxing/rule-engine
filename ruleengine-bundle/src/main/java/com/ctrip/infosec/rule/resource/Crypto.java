/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ctrip.infosec.rule.resource;

import static com.ctrip.infosec.common.SarsMonitorWrapper.afterInvoke;
import static com.ctrip.infosec.common.SarsMonitorWrapper.beforeInvoke;
import static com.ctrip.infosec.common.SarsMonitorWrapper.fault;
import com.ctrip.infosec.configs.rule.trace.logger.TraceLogger;
import com.ctrip.infosec.rule.Contexts;
import com.ctrip.infosec.sars.util.GlobalConfig;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 加解密组件
 *
 * @author zhengby
 */
public class Crypto {

    private static Logger logger = LoggerFactory.getLogger(Crypto.class);

    static final String cscmUrl = GlobalConfig.getString("CryptoGraphy.cscmUrl");
    static final String sslcode = GlobalConfig.getString("CryptoGraphy.sslcode");
    static final String env = GlobalConfig.getString("CryptoGraphy.dependency.env");

    static final String DEV = "DEV";
    static final String PROD = "PROD";

    static com.ctrip.infosec.encrypt.CryptoGraphy cryptoGraphyProd;
    static com.ctrip.infosec.dev.encrypt.CryptoGraphy cryptoGraphyDev;

    static Lock lock = new ReentrantLock();

    static void init() {
        Validate.notEmpty(cscmUrl, "在GlobalConfig.properties里没有找到\"CryptoGraphy.cscmUrl\"配置项.");
        Validate.notEmpty(sslcode, "在GlobalConfig.properties里没有找到\"CryptoGraphy.sslcode\"配置项.");
        Validate.notEmpty(env, "在GlobalConfig.properties里没有找到\"CryptoGraphy.dependency.env\"配置项.");

        if (PROD.equals(env)) {
            if (cryptoGraphyProd == null) {
                lock.lock();
                try {
                    if (cryptoGraphyProd == null) {
                        com.ctrip.infosec.encrypt.CryptoGraphy _cryptoGraphyProd = com.ctrip.infosec.encrypt.CryptoGraphy.GetInstance();
                        _cryptoGraphyProd.init(cscmUrl, sslcode);
                        cryptoGraphyProd = _cryptoGraphyProd;
                    }
                } finally {
                    lock.unlock();
                }
            }
        } else {
            lock.lock();
            try {
                if (cryptoGraphyDev == null) {
                    com.ctrip.infosec.dev.encrypt.CryptoGraphy _cryptoGraphyDev = com.ctrip.infosec.dev.encrypt.CryptoGraphy.GetInstance();
                    _cryptoGraphyDev.init(cscmUrl, sslcode);
                    cryptoGraphyDev = _cryptoGraphyDev;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public static String encrypt(String plain) {
        init();
        beforeInvoke("Crypto.encrypt");
        String cypher = null;
        try {
            if (PROD.equals(env)) {
                cypher = cryptoGraphyProd.encrypt(plain);
            } else {
                cypher = cryptoGraphyDev.encrypt(plain);
            }
        } catch (Exception ex) {
            fault("Crypto.encrypt");
            logger.warn(Contexts.getLogPrefix() + "encrypt fault. plain=" + plain, ex);
        } finally {
            afterInvoke("Crypto.encrypt");
        }
        return cypher;
    }

    public static String decrypt(String complexText) {
        init();
        beforeInvoke("Crypto.decrypt");
        String txt = null;
        try {
            if (PROD.equals(env)) {
                txt = cryptoGraphyProd.decrypt(complexText);
            } else {
                txt = cryptoGraphyDev.decrypt(complexText);
            }
        } catch (Exception ex) {
            fault("Crypto.decrypt");
            logger.warn(Contexts.getLogPrefix() + "decrypt fault. complexText=" + complexText, ex);
            TraceLogger.traceLog("解密异常: complexText=" + complexText + ", EXCEPTION: " + ex.toString());
        } finally {
            afterInvoke("Crypto.decrypt");
        }
        return txt;
    }
}
