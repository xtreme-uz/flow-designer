package uz.xtreme.flowdesigner.service.flow.dto.thub;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * THUB flow_status_action table record.
 */
public record ThubFlowStatusAction(
        @JsonProperty("flowtypeid")
        String flowTypeId,
        @JsonProperty("flowstatusid")
        String flowStatusId,
        @JsonProperty("actionmoduleid")
        String actionModuleId,
        @JsonProperty("actionid")
        String actionId,
        @JsonProperty("maxactiontriescount")
        Integer maxActionTriesCount,
        @JsonProperty("maxactiontryingtime")
        String maxActionTryingTime,
        @JsonProperty("warningactiontryingtime")
        String warningActionTryingTime
) {
}
