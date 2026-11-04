package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketplaceLoginTargetTest {

    @Test
    void escolheLoginPeloSufixoDaOrigem() {
        assertEquals(MarketplaceLoginTarget.UPWORK, MarketplaceLoginTarget.fromOrigem("polling:UPWORK"));
        assertEquals(MarketplaceLoginTarget.FREELANCER, MarketplaceLoginTarget.fromOrigem("inbox:freelancer"));
        assertEquals(MarketplaceLoginTarget.FREELAS_99, MarketplaceLoginTarget.fromOrigem("polling:99FREELAS"));
        assertEquals(MarketplaceLoginTarget.FREELAS_99, MarketplaceLoginTarget.fromOrigem("dashboard-overview"));
        assertEquals(MarketplaceLoginTarget.FREELAS_99, MarketplaceLoginTarget.fromOrigem(null));
    }
}
