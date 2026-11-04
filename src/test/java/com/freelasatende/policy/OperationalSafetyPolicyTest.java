package com.freelasatende.policy;

import com.freelasatende.model.enums.ModoSistema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalSafetyPolicyTest {

    private final OperationalSafetyPolicy policy = new OperationalSafetyPolicy();

    @Test
    void clientOutputRequiresHumanApprovalAndExplicitWritePath() {
        assertTrue(policy.requiresHumanApprovalBeforeClientSend(ModoSistema.ENSINAMENTO));
        assertFalse(policy.requiresHumanApprovalBeforeClientSend(ModoSistema.REGULAR));
        assertFalse(policy.requiresHumanApprovalBeforeClientSend(ModoSistema.LIVRE));
        assertFalse(policy.clientWritePathEnabled(false, false));
        assertTrue(policy.clientWritePathEnabled(true, false));
        assertTrue(policy.clientWritePathEnabled(false, true));
        assertTrue(policy.shouldSimulateClientWrite(false, true));
        assertFalse(policy.shouldSimulateClientWrite(true, true));
        assertFalse(policy.allowsAutomaticClientSend(ModoSistema.ENSINAMENTO, true, false));
        assertTrue(policy.allowsAutomaticClientSend(ModoSistema.REGULAR, true, false));
        assertTrue(policy.allowsAutomaticClientSend(ModoSistema.LIVRE, false, true));
    }

    @Test
    void mlLabSafetySnapshotKeepsExternalWritesBlocked() {
        var safety = policy.mlLabSafetySnapshot(true, "", null, "MULTI_CRITERIA", 6);

        assertTrue(policy.externalWritesBlockedInLab());
        assertEquals("bloqueado", safety.get("envioCliente"));
        assertEquals("nao alterada", safety.get("memoriaPrincipal"));
        assertEquals("habilitada", safety.get("iaReal"));
        assertEquals("-", safety.get("provider"));
        assertEquals("-", safety.get("modelo"));
    }
}
