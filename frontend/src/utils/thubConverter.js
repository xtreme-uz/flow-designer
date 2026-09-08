import { applyDagreLayout } from './layoutUtils';

/**
 * Convert THUB deployment data to React Flow nodes and edges.
 *
 * @param {Object} deploymentData - ThubDeploymentData from backend
 * @param {Object|null} layout - saved canvas layout ({ nodes: [{ statusId, x, y }] }).
 *   Nodes it covers keep the position the user left them at; the rest fall back
 *   to dagre, so a flow saved before layouts existed still opens sensibly.
 * @returns {{ nodes: Array, edges: Array }}
 */
export function thubToReactFlow(deploymentData, layout = null) {
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

  const savedPositions = new Map(
    (layout?.nodes ?? [])
      .filter((n) => n.statusId && Number.isFinite(n.x) && Number.isFinite(n.y))
      .map((n) => [n.statusId, { x: n.x, y: n.y }])
  );

  // Dagre still runs: it positions anything the layout does not cover
  const positionedNodes = applyDagreLayout(nodes, edges).map((node) =>
    savedPositions.has(node.id)
      ? { ...node, position: savedPositions.get(node.id) }
      : node
  );

  return { nodes: positionedNodes, edges };
}

/**
 * Convert React Flow nodes and edges back to ThubDeploymentData.
 *
 * @param {Array} nodes - React Flow nodes
 * @param {Array} edges - React Flow edges
 * @param {string} flowTypeId - Flow type identifier
 * @param {Object|null} existingFlowType - Existing flowType record to preserve audit info
 * @param {Array} existingAssignments - FlowAssignment records of the loaded flow.
 *   The canvas cannot express assignments, so they are carried through unchanged;
 *   dropping them here would delete them from the THUB data on the next save.
 * @returns {Object} ThubDeploymentData
 */
export function reactFlowToThub(nodes, edges, flowTypeId, existingFlowType = null, existingAssignments = []) {
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

  // Build flow status transitions.
  // THUB keys a transition by (flowType, status, nextStatus), so two edges
  // between the same pair collapse into one record — the UI prevents drawing
  // them and the backend rejects them, this is the last line of defence.
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
    flowAssignments: existingAssignments ?? [],
  };
}

/**
 * Canvas layout for the current nodes: where each one sits, and which statuses
 * the flow is made of. The backend needs the second part because THUB has no
 * per-flow status list — without it a status with no action and no transition
 * cannot be told apart from another flow's status when the flow is reopened.
 *
 * @param {Array} nodes - React Flow nodes
 * @returns {{ nodes: Array<{ statusId: string, x: number, y: number }> }}
 */
export function reactFlowToLayout(nodes) {
  return {
    nodes: nodes
      .filter((node) => node.data?.statusId)
      .map((node) => ({
        statusId: node.data.statusId,
        x: Math.round(node.position?.x ?? 0),
        y: Math.round(node.position?.y ?? 0),
      })),
  };
}
