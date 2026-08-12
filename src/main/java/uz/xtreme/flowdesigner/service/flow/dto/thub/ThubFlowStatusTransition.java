package uz.xtreme.flowdesigner.service.flow.dto.thub;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * THUB flow_status_transition table record.
 */
public record ThubFlowStatusTransition(
        @JsonProperty("flowtypeid")
        String flowTypeId,
        @JsonProperty("flowstatusid")
        String flowStatusId,
        @JsonProperty("nextflowstatusid")
        String nextFlowStatusId,
        @JsonProperty("actionresulttypeids")
        String actionResultTypeIds,
        @JsonProperty("storeactionresultasrequestresult")
        Boolean storeActionResultAsRequestResult
) {
}
