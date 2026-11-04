package com.freelasatende.service.impl;

import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAssistidoAutoTriggerTest {

    @Test
    void sessaoUpworkUsaLoginUpworkENaoValidaSessao99Freelas() {
        ObjectProvider<LoginJavaDriverless> login99Provider = provider();
        ObjectProvider<UpworkLoginDriverless> upworkProvider = provider();
        ObjectProvider<FreelancerLoginDriverless> freelancerProvider = provider();
        LoginJavaDriverless login99 = mock(LoginJavaDriverless.class);
        UpworkLoginDriverless loginUpwork = mock(UpworkLoginDriverless.class);
        AutenticacaoService autenticacao99 = mock(AutenticacaoService.class);
        ObjectProvider<PollingScheduler> schedulerProvider = provider();
        when(login99Provider.getIfAvailable()).thenReturn(login99);
        when(upworkProvider.getIfAvailable()).thenReturn(loginUpwork);
        when(loginUpwork.executarLogin()).thenReturn(false);

        LoginAssistidoAutoTrigger trigger = new LoginAssistidoAutoTrigger(
            login99Provider,
            upworkProvider,
            freelancerProvider,
            provider(),
            autenticacao99,
            schedulerProvider
        );

        trigger.executarLogin("polling:UPWORK");

        verify(loginUpwork).executarLogin();
        verify(login99, never()).executarLogin();
        verify(autenticacao99, never()).verificarSessaoValida();
    }

    @Test
    void sessaoFreelancerUsaLoginFreelancer() {
        ObjectProvider<LoginJavaDriverless> login99Provider = provider();
        ObjectProvider<UpworkLoginDriverless> upworkProvider = provider();
        ObjectProvider<FreelancerLoginDriverless> freelancerProvider = provider();
        FreelancerLoginDriverless loginFreelancer = mock(FreelancerLoginDriverless.class);
        when(freelancerProvider.getIfAvailable()).thenReturn(loginFreelancer);
        when(loginFreelancer.executarLogin()).thenReturn(false);

        LoginAssistidoAutoTrigger trigger = new LoginAssistidoAutoTrigger(
            login99Provider,
            upworkProvider,
            freelancerProvider,
            provider(),
            mock(AutenticacaoService.class),
            provider()
        );

        trigger.executarLogin("polling:FREELANCER");

        verify(loginFreelancer).executarLogin();
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
