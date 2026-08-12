package uz.xtreme.flowdesigner.service.flow.dto.thub;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * THUB flow_assignment table record.
 */
public record ThubFlowAssignment(
        String id,
        @JsonProperty("sourcepaymentactorid")
        String sourcePaymentActorId,
        @JsonProperty("targetpaymentactorid")
        String targetPaymentActorId,
        @JsonProperty("paymenttype")
        String paymentType,
        @JsonProperty("flowtypeid")
        String flowTypeId
) {
}
