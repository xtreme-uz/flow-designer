package uz.xtreme.flowdesigner.service.flow.dto;

import java.util.List;

/**
 * Where a flow's nodes sit on the canvas, and which statuses the flow is made of.
 *
 * <p>This is Flow Designer's own state, not THUB data: it lives beside the THUB
 * files under {@code .flowdesigner/} so the configuration deployer never sees it.
 * It carries the node list because THUB has no per-flow status table — statuses
 * are shared — so a status a flow owns but has not wired to anything yet is
 * otherwise indistinguishable from another flow's status.
 */
public record FlowLayout(List<NodePosition> nodes) {

    public FlowLayout {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static FlowLayout empty() {
        return new FlowLayout(List.of());
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public List<String> statusIds() {
        return nodes.stream().map(NodePosition::statusId).filter(id -> id != null && !id.isBlank()).toList();
    }

    /**
     * @param statusId the status this node stands for
     * @param x        canvas coordinate, as the user left it
     * @param y        canvas coordinate, as the user left it
     */
    public record NodePosition(String statusId, double x, double y) {
    }
}
