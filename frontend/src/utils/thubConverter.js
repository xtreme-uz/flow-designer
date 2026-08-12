import { applyDagreLayout } from './layoutUtils';

/**
 * Convert THUB deployment data to React Flow nodes and edges.
 * Positions are computed via dagre auto-layout.
 *
 * @param {Object} deploymentData - ThubDeploymentData from backend
 * @returns {{ nodes: Array, edges: Array }}
 */
export function thubToReactFlow(deploymentData) {
  const { flowType, flowStatuses, flowStatusActions, flowStatusTransitions } = deploymentData;

  const initialStatusId = flowType?.initialflowstatusid ?? flowType?.initialFlowStatusId;
  const finalStatusId = flowType?.finalflowstatusid ?? flowType?.finalFlowStatusId;

  // Build nodes from statuses
  const nodes = flowStatuses.map((status) => {
    const isInitial = status.id === initialStatusId;
    const isFinal = status.id === finalStatusId;

    let nodeType = 'statusNode';
    if (isInitial) nodeType = 'initialNode';
    else if (isFinal) nodeType = 'finalNode';

    // Find action for this status
    const action = flowStatusActions.find(
      (a) => (a.flowstatusid ?? a.flowStatusId) === status.id
    );

    return {
      id: status.id,
      type: nodeType,
      position: { x: 0, y: 0 }, // will be set by dagre
      data: {
        statusId: status.id,
        description: status.description || '',
        isInitial,
        isFinal,
        action: action
          ? {
              moduleId: action.actionmoduleid ?? action.actionModuleId ?? '',
              actionId: action.actionid ?? action.actionId ?? '',
              maxTriesCount: action.maxactiontriescount ?? action.maxActionTriesCount ?? 3,
              maxTryingTime: action.maxactiontryingtime ?? action.maxActionTryingTime ?? '',
              warningTryingTime: action.warningactiontryingtime ?? action.warningActionTryingTime ?? '',
            }
          : null,
      },
    };
  });

  // Build edges from transitions
  const edges = flowStatusTransitions.map((t, index) => {
    const sourceId = t.flowstatusid ?? t.flowStatusId;
    const targetId = t.nextflowstatusid ?? t.nextFlowStatusId;
    const resultType = t.actionresulttypeids ?? t.actionResultTypeIds ?? 'success';
    const storeAsResult = t.storeactionresultasrequestresult ?? t.storeActionResultAsRequestResult ?? true;

    return {
      id: `e_${sourceId}_${targetId}_${index}`,
      source: sourceId,
      target: targetId,
      type: 'smoothstep',
      animated: true,
      label: resultType,
      data: {
        actionResultTypeIds: resultType,
        storeAsRequestResult: storeAsResult,
      },
    };
  });

  // Apply dagre layout
  const positionedNodes = applyDagreLayout(nodes, edges);

  return { nodes: positionedNodes, edges };
}

/**
 * Convert React Flow nodes and edges back to ThubDeploymentData.
 *
 * @param {Array} nodes - React Flow nodes
 * @param {Array} edges - React Flow edges
 * @param {string} flowTypeId - Flow type identifier
 * @param {Object|null} existingFlowType - Existing flowType record to preserve audit info
 * @returns {Object} ThubDeploymentData
 */
export function reactFlowToThub(nodes, edges, flowTypeId, existingFlowType = null) {
  // Determine initial and final status
  const initialNode = nodes.find((n) => n.type === 'initialNode' || n.data?.isInitial);
  const finalNode = nodes.find((n) => n.type === 'finalNode' || n.data?.isFinal);

  const initialStatusId = initialNode?.data?.statusId || initialNode?.id || '';
  const finalStatusId = finalNode?.data?.statusId || finalNode?.id || '';

  // Build flowType
  const flowType = {
    id: flowTypeId,
    initialflowstatusid: initialStatusId,
    finalflowstatusid: finalStatusId,
    description: existingFlowType?.description ?? '',
    version: existingFlowType?.version ?? '1.0',
    component: existingFlowType?.component ?? 'THUB',
    createdby: existingFlowType?.createdby ?? existingFlowType?.createdBy ?? null,
    createdat: existingFlowType?.createdat ?? existingFlowType?.createdAt ?? null,
    lastmodifiedby: existingFlowType?.lastmodifiedby ?? existingFlowType?.lastModifiedBy ?? null,
    lastmodifiedat: existingFlowType?.lastmodifiedat ?? existingFlowType?.lastModifiedAt ?? null,
    categorization: existingFlowType?.categorization ?? {},
  };

  // Build flow statuses
  const flowStatuses = nodes.map((node) => ({
    id: node.data?.statusId || node.id,
    description: node.data?.description || '',
  }));

  // Build flow status actions
  const flowStatusActions = nodes
    .filter((node) => node.data?.action?.moduleId || node.data?.action?.actionId)
    .map((node) => ({
      flowtypeid: flowTypeId,
      flowstatusid: node.data?.statusId || node.id,
      actionmoduleid: node.data.action.moduleId || '',
      actionid: node.data.action.actionId || '',
      maxactiontriescount: node.data.action.maxTriesCount ?? 3,
      maxactiontryingtime: node.data.action.maxTryingTime || null,
      warningactiontryingtime: node.data.action.warningTryingTime || null,
    }));

  // Build node ID → statusId mapping (React Flow node IDs may differ from statusIds)
  const nodeIdToStatusId = {};
  for (const node of nodes) {
    nodeIdToStatusId[node.id] = node.data?.statusId || node.id;
  }

  // Build flow status transitions
  const flowStatusTransitions = edges.map((edge) => ({
    flowtypeid: flowTypeId,
    flowstatusid: nodeIdToStatusId[edge.source] || edge.source,
    nextflowstatusid: nodeIdToStatusId[edge.target] || edge.target,
    actionresulttypeids: edge.data?.actionResultTypeIds || edge.label || 'success',
    storeactionresultasrequestresult: edge.data?.storeAsRequestResult ?? true,
  }));

  return {
    flowType,
    flowStatuses,
    flowStatusActions,
    flowStatusTransitions,
    flowAssignments: existingFlowType?._flowAssignments ?? [],
  };
}
