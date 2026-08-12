import Dagre from '@dagrejs/dagre';

const NODE_WIDTH = 250;
const NODE_HEIGHT = 80;

/**
 * Apply dagre auto-layout to nodes and edges.
 * @param {Array} nodes - React Flow nodes
 * @param {Array} edges - React Flow edges
 * @param {Object} options - Layout options
 * @returns {Array} Positioned nodes
 */
export function applyDagreLayout(nodes, edges, options = {}) {
  const {
    direction = 'TB',
    nodesep = 120,
    ranksep = 150,
  } = options;

  const g = new Dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: direction, nodesep, ranksep });

  nodes.forEach((node) => {
    g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  });

  edges.forEach((edge) => {
    g.setEdge(edge.source, edge.target);
  });

  Dagre.layout(g);

  return nodes.map((node) => {
    const pos = g.node(node.id);
    return {
      ...node,
      position: {
        x: pos.x - NODE_WIDTH / 2,
        y: pos.y - NODE_HEIGHT / 2,
      },
    };
  });
}
