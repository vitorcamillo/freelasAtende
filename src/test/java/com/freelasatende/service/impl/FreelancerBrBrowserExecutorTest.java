package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import io.github.selenium.javaDriverless.JavaDriverless;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regressao: pool travado em CDP, desanexo condicional, lock no ocioso e orfao na construcao. */
class FreelancerBrBrowserExecutorTest {

    private FreelancerBrBrowser novoBrowser(JavaDriverless driver) {
        FreelancerBrBrowser browser = new FreelancerBrBrowser(
            new FreelancerBrConfig(), new ObjectMapper(), new ChromeSessaoLock(),
            mock(FreelancerBrClient.class));
        browser.fabricaDriver = options -> driver;
        browser.timeoutOperacaoSegundos = 5;
        return browser;
    }

    private static JavaDriverless driverFalso() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.getPid()).thenReturn(0L);
        return driver;
    }

    @Test
    void desanexarComEsperadoDiferentePreservaDriverDeOutraRodada() {
        FreelancerBrBrowser browser = novoBrowser(driverFalso());
        JavaDriverless residente = driverFalso();
        JavaDriverless outraRodada = driverFalso();
        browser.driverResidente = residente;

        assertNull(browser.desanexarDriver(outraRodada));
        assertSame(residente, browser.driverResidente);
        assertSame(residente, browser.desanexarDriver(residente));
        assertNull(browser.driverResidente);
    }

    @Test
    void fecharOciosoRespeitaLockGlobal() throws Exception {
        ChromeSessaoLock lock = new ChromeSessaoLock();
        FreelancerBrBrowser browser = new FreelancerBrBrowser(
            new FreelancerBrConfig(), new ObjectMapper(), lock, mock(FreelancerBrClient.class));
        JavaDriverless driver = driverFalso();
        browser.driverResidente = driver;
        browser.ultimoUsoMillis = System.currentTimeMillis() - 20 * 60_000L;
        ReflectionTestUtils.setField(browser, "chromeOciosoMinutos", 10L);

        assertTrue(lock.adquirir("teste"));
        try {
            browser.fecharChromeOcioso();
            verify(driver, never()).quit();
            assertSame(driver, browser.driverResidente);
        } finally {
            lock.liberar();
        }
        browser.fecharChromeOcioso();
        verify(driver).quit();
        assertNull(browser.driverResidente);
    }

    @Test
    void poolTravadoRecriaExecutorERodadaSeguinteExecuta() throws Exception {
        FreelancerBrBrowser browser = novoBrowser(driverFalso());
        Object executorAntes = browser.navegadorExecutor;
        CountDownLatch entrou = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        FreelancerBrBrowser.AcaoNavegador<String> travada = driver -> {
            entrou.countDown();
            long limite = System.currentTimeMillis() + 10_000L;
            while (liberar.getCount() > 0 && System.currentTimeMillis() < limite) {
                try {
                    liberar.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignorada) {
                    // CDP travado ignora interrupcao; segue preso ate liberar.
                }
            }
            return "presa";
        };
        ExecutorService chamadores = Executors.newFixedThreadPool(2);
        try {
            Future<String> primeira = chamadores.submit(() -> browser.comNavegador(travada));
            Future<String> segunda = chamadores.submit(() -> browser.comNavegador(travada));
            assertTrue(entrou.await(3, TimeUnit.SECONDS));
            long limite = System.currentTimeMillis() + 3_000L;
            while (browser.navegadorExecutor.getPoolSize() < 2 && System.currentTimeMillis() < limite) {
                Thread.sleep(20);
            }
            assertEquals(2, browser.navegadorExecutor.getPoolSize());

            assertNull(browser.comNavegador(driver -> "x"));
            assertNull(browser.comNavegador(driver -> "x"));
            assertNull(browser.comNavegador(driver -> "x"));
            assertNotSame(executorAntes, browser.navegadorExecutor);

            liberar.countDown();
            assertEquals("presa", primeira.get(5, TimeUnit.SECONDS));
            String segundaResultado = segunda.get(5, TimeUnit.SECONDS);
            assertTrue(segundaResultado == null || "presa".equals(segundaResultado),
                "Segunda chamada pode abortar ao ser interrompida esperando o lock na recriacao");
            assertEquals("ok", browser.comNavegador(driver -> "ok"));
        } finally {
            liberar.countDown();
            chamadores.shutdownNow();
            browser.navegadorExecutor.shutdownNow();
        }
    }

    @Test
    void timeoutDuranteConstrucaoNaoPromoveDriverOrfao() throws Exception {
        FreelancerBrBrowser browser = novoBrowser(driverFalso());
        browser.timeoutOperacaoSegundos = 1;
        AtomicBoolean acaoExecutou = new AtomicBoolean(false);
        browser.fabricaDriver = options -> {
            long limite = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < limite) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ignorada) {
                    // Construtor real pode ignorar interrupcao e concluir depois do timeout.
                }
            }
            return driverFalso();
        };

        assertNull(browser.comNavegador(driver -> {
            acaoExecutou.set(true);
            return "x";
        }));
        Thread.sleep(1_800L);
        assertNull(browser.driverResidente);
        assertTrue(!acaoExecutou.get());
        browser.navegadorExecutor.shutdownNow();
    }
}
