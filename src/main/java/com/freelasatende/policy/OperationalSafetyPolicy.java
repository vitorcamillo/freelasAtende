package com.freelasatende.policy;

import com.freelasatende.model.enums.ModoSistema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OperationalSafetyPolicy {

    public boolean requiresHumanApprovalBeforeClientSend(ModoSistema modo) {
        return modo == ModoSistema.ENSINAMENTO;
    }

    public boolean allowsAutomaticClientSend(ModoSistema modo, boolean realWriteEnabled, boolean simulateEnabled) {
        return !requiresHumanApprovalBeforeClientSend(modo)
            && clientWritePathEnabled(realWriteEnabled, simulateEnabled);
    }

    public boolean clientWritePathEnabled(boolean realWriteEnabled, boolean simulateEnabled) {
        return realWriteEnabled || simulateEnabled;
    }

    public boolean shouldSimulateClientWrite(boolean realWriteEnabled, boolean simulateEnabled) {
        return !realWriteEnabled && simulateEnabled;
    }

    public boolean externalWritesBlockedInLab() {
        return true;
    }

    public String labCorpusSummarySuffix() {
        return "Pasta livre para ensino: data/ml-lab/ingest. Escrita externa bloqueada.";
    }

    public String writeDisabledOperatorMessage() {
        return "Escrita real desabilitada: real.write.enabled=false";
    }

    public String writeDisabledUserMessage() {
        return "🛑 Escrita real desabilitada. Para enviar ao cliente, reinicie com -Dreal.write.enabled=true. Nada foi enviado.";
    }

    public String simulatedClientSendLogMessage() {
        return "Envio simulado apos aprovacao humana. Nenhuma chamada real ao cliente foi feita.";
    }

    public Map<String, Object> mlLabSafetySnapshot(
        boolean realIaEnabled,
        String provider,
        String model,
        String selectionMode,
        int parallelWorkers
    ) {
        Map<String, Object> safety = new LinkedHashMap<>();
        safety.put("telegram", "nao usado");
        safety.put("scraper", "nao usado");
        safety.put("envioCliente", externalWritesBlockedInLab() ? "bloqueado" : "habilitado");
        safety.put("memoriaPrincipal", "nao alterada");
        safety.put("iaReal", realIaEnabled ? "habilitada" : "offline");
        safety.put("provider", valueOrDash(provider));
        safety.put("modelo", valueOrDash(model));
        safety.put("selectionMode", selectionMode);
        safety.put("parallelWorkers", parallelWorkers);
        return safety;
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
