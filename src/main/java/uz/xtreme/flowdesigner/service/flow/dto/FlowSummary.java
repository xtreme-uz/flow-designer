package uz.xtreme.flowdesigner.service.flow.dto;

import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubFlowType;

import java.time.Instant;

/**
 * Summary information about a flow for listing purposes.
 */
public record FlowSummary(
        String name,
        String flowTypeId,
        String description,
        String version,
        String lastModifiedBy,
        Instant lastModifiedAt
) {
    public static FlowSummary from(ThubFlowType flowType) {
        if (flowType == null) {
            return new FlowSummary("unknown", "unknown", null, "1.0", null, null);
        }
        return new FlowSummary(
                flowType.id(),
                flowType.id(),
                flowType.description(),
                flowType.version(),
                flowType.lastModifiedBy(),
                flowType.lastModifiedAt()
        );
    }
}
