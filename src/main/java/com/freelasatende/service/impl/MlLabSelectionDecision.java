package com.freelasatende.service.impl;

import com.freelasatende.model.entity.MlLabAgent;

record MlLabSelectionDecision(MlLabAgent agent, double fitnessScore, String band, boolean survives, String reason) {
}
