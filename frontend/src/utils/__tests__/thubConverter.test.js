import { describe, expect, it } from 'vitest';
import { reactFlowToLayout, reactFlowToThub, thubToReactFlow } from '../thubConverter';

/** Minimal THUB payload shaped like what the backend returns. */
function deploymentData(overrides = {}) {
  return {
    flowType: {
      id: 'payment-flow',
      initialflowstatusid: 'ACCEPTED',
      finalflowstatusid: 'FINISHED',
      description: 'Card payment',
      version: '1.0',
      component: 'THUB',
      categorization: {},
    },
    flowStatuses: [
      { id: 'ACCEPTED', description: 'Payment accepted' },
      { id: 'SRC_DEBITED', description: 'Source debited' },
      { id: 'FINISHED', description: 'Flow completed' },
    ],
    flowStatusActions: [
      {
        flowtypeid: 'payment-flow',
        flowstatusid: 'ACCEPTED',
        actionmoduleid: 'source-payment-actor',
        actionid: 'realize-debit',
        maxactiontriescount: 5,
        maxactiontryingtime: '30m',
        warningactiontryingtime: '10m',
      },
    ],
    flowStatusTransitions: [
      {
        flowtypeid: 'payment-flow',
        flowstatusid: 'ACCEPTED',
        nextflowstatusid: 'SRC_DEBITED',
        actionresulttypeids: 'success',
        storeactionresultasrequestresult: true,
      },
    ],
    flowAssignments: [
      {
        id: 'card-processing',
        sourcepaymentactorid: 'bank',
        targetpaymentactorid: 'merchant',
        paymenttype: 'CARD',
        flowtypeid: 'payment-flow',
      },
    ],
    ...overrides,
  };
}

describe('thubToReactFlow', () => {
  it('types the initial and final statuses from the flow type', () => {
    const { nodes } = thubToReactFlow(deploymentData());

    expect(nodes.find((n) => n.id === 'ACCEPTED').type).toBe('initialNode');
    expect(nodes.find((n) => n.id === 'FINISHED').type).toBe('finalNode');
    expect(nodes.find((n) => n.id === 'SRC_DEBITED').type).toBe('statusNode');
  });

  it('attaches the action of a status to its node', () => {
    const { nodes } = thubToReactFlow(deploymentData());
    const accepted = nodes.find((n) => n.id === 'ACCEPTED');

    expect(accepted.data.action).toMatchObject({
      moduleId: 'source-payment-actor',
      actionId: 'realize-debit',
      maxTriesCount: 5,
    });
    expect(nodes.find((n) => n.id === 'FINISHED').data.action).toBeNull();
  });

  it('keeps the positions a saved layout provides', () => {
    const layout = { nodes: [{ statusId: 'SRC_DEBITED', x: 400, y: 250 }] };

    const { nodes } = thubToReactFlow(deploymentData(), layout);

    expect(nodes.find((n) => n.id === 'SRC_DEBITED').position).toEqual({ x: 400, y: 250 });
    // Everything the layout does not cover still comes from dagre
    const accepted = nodes.find((n) => n.id === 'ACCEPTED');
    expect(Number.isFinite(accepted.position.x)).toBe(true);
  });

  it('ignores layout entries with missing coordinates', () => {
    const layout = { nodes: [{ statusId: 'SRC_DEBITED', x: null, y: 250 }] };

    const { nodes } = thubToReactFlow(deploymentData(), layout);

    expect(nodes.find((n) => n.id === 'SRC_DEBITED').position.x).not.toBeNull();
  });

  it('gives every node a position from the layout', () => {
    const { nodes } = thubToReactFlow(deploymentData());

    expect(nodes.every((n) => Number.isFinite(n.position.x) && Number.isFinite(n.position.y))).toBe(true);
  });
});

describe('reactFlowToThub', () => {
  const nodes = [
    { id: 'ACCEPTED', type: 'initialNode', data: { statusId: 'ACCEPTED', description: 'Payment accepted' } },
    { id: 'FINISHED', type: 'finalNode', data: { statusId: 'FINISHED', description: 'Flow completed' } },
  ];
  const edges = [
    {
      id: 'e1',
      source: 'ACCEPTED',
      target: 'FINISHED',
      data: { actionResultTypeIds: 'success', storeAsRequestResult: true },
    },
  ];

  it('keeps the assignments of the loaded flow', () => {
    const assignments = deploymentData().flowAssignments;

    const result = reactFlowToThub(nodes, edges, 'payment-flow', null, assignments);

    expect(result.flowAssignments).toEqual(assignments);
  });

  it('sends no assignments when the flow has none', () => {
    const result = reactFlowToThub(nodes, edges, 'payment-flow');

    expect(result.flowAssignments).toEqual([]);
  });

  it('carries the flow type audit fields through unchanged', () => {
    const existing = {
      description: 'Card payment',
      version: '2.1',
      component: 'THUB',
      createdby: 'alisher',
      createdat: '2026-01-01T00:00:00Z',
    };

    const result = reactFlowToThub(nodes, edges, 'payment-flow', existing);

    expect(result.flowType).toMatchObject({
      version: '2.1',
      createdby: 'alisher',
      createdat: '2026-01-01T00:00:00Z',
      initialflowstatusid: 'ACCEPTED',
      finalflowstatusid: 'FINISHED',
    });
  });

  it('maps node ids to status ids on transitions', () => {
    const renamedNodes = [
      { id: 'node_0', type: 'initialNode', data: { statusId: 'ACCEPTED' } },
      { id: 'node_1', type: 'finalNode', data: { statusId: 'FINISHED' } },
    ];
    const renamedEdges = [{ id: 'e1', source: 'node_0', target: 'node_1', data: {} }];

    const result = reactFlowToThub(renamedNodes, renamedEdges, 'payment-flow');

    expect(result.flowStatusTransitions[0]).toMatchObject({
      flowstatusid: 'ACCEPTED',
      nextflowstatusid: 'FINISHED',
      actionresulttypeids: 'success',
    });
  });
});

describe('round trip', () => {
  it('preserves statuses, transitions and assignments', () => {
    const original = deploymentData();
    const { nodes, edges } = thubToReactFlow(original);

    const result = reactFlowToThub(nodes, edges, 'payment-flow', original.flowType, original.flowAssignments);

    expect(result.flowStatuses.map((s) => s.id).sort()).toEqual(['ACCEPTED', 'FINISHED', 'SRC_DEBITED']);
    expect(result.flowStatusTransitions).toHaveLength(1);
    expect(result.flowAssignments).toEqual(original.flowAssignments);
  });
});

describe('reactFlowToLayout', () => {
  it('records the status id and rounded position of every named node', () => {
    const nodes = [
      { id: 'n1', data: { statusId: 'ACCEPTED' }, position: { x: 10.4, y: 20.6 } },
      { id: 'n2', data: { statusId: 'FINISHED' }, position: { x: 300, y: 0 } },
    ];

    expect(reactFlowToLayout(nodes)).toEqual({
      nodes: [
        { statusId: 'ACCEPTED', x: 10, y: 21 },
        { statusId: 'FINISHED', x: 300, y: 0 },
      ],
    });
  });

  it('skips nodes that have no status id yet', () => {
    const nodes = [{ id: 'node_0', data: { statusId: '' }, position: { x: 0, y: 0 } }];

    expect(reactFlowToLayout(nodes).nodes).toEqual([]);
  });
});
