package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.service.flow.dto.FlowLayout;

import java.nio.file.Path;

/**
 * Reads and writes the canvas layout kept alongside a repository's THUB data.
 *
 * <p>Files live under {@code .flowdesigner/flows/{flowTypeId}.json}, outside the
 * {@code THUB/} tree the configuration deployer reads.
 */
public interface FlowLayoutService {

    String LAYOUT_DIR = ".flowdesigner";

    /**
     * @return the stored layout, or an empty one when the flow has none — a flow
     *         saved before layouts existed, or by another tool
     */
    FlowLayout read(Path basePath, String flowTypeId);

    void write(Path basePath, String flowTypeId, FlowLayout layout);

    /**
     * Removes a flow's layout. Silent when there is nothing to remove.
     */
    void delete(Path basePath, String flowTypeId);
}
