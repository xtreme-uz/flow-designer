package uz.xtreme.flowdesigner.service.flow.dto.thub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * THUB flow_type table record.
 * Also stores audit metadata (formerly in FlowMetadata).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThubFlowType(
        String id,
        @JsonProperty("initialflowstatusid")
        String initialFlowStatusId,
        @JsonProperty("finalflowstatusid")
        String finalFlowStatusId,
        String description,
        String version,
        String component,
        @JsonProperty("createdby")
        String createdBy,
        @JsonProperty("createdat")
        Instant createdAt,
        @JsonProperty("lastmodifiedby")
        String lastModifiedBy,
        @JsonProperty("lastmodifiedat")
        Instant lastModifiedAt,
        Map<String, String> categorization
) {
    public ThubFlowType {
        if (version == null || version.isBlank()) {
            version = "1.0";
        }
        if (component == null || component.isBlank()) {
            component = "THUB";
        }
        if (categorization == null) {
            categorization = Map.of();
        }
    }

    public static ThubFlowType create(String id, String description, String createdBy) {
        Instant now = Instant.now();
        return new ThubFlowType(
                id, null, null, description,
                "1.0", "THUB",
                createdBy, now, createdBy, now,
                Map.of()
        );
    }

    public ThubFlowType withModification(String modifiedBy) {
        return new ThubFlowType(
                id, initialFlowStatusId, finalFlowStatusId, description,
                version, component,
                createdBy, createdAt, modifiedBy, Instant.now(),
                categorization
        );
    }

    public ThubFlowType withStatuses(String initialStatusId, String finalStatusId) {
        return new ThubFlowType(
                id, initialStatusId, finalStatusId, description,
                version, component,
                createdBy, createdAt, lastModifiedBy, lastModifiedAt,
                categorization
        );
    }
}
