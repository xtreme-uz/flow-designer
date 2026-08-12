package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.exception.FlowStorageException;
import uz.xtreme.flowdesigner.service.flow.dto.thub.*;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implementation of ThubDataService.
 * Reads/writes shared THUB data files in the configurator pattern.
 *
 * Directory structure:
 * THUB/
 * ├── FlowType-{metadata,definition,data}.json
 * ├── FlowStatus-{metadata,definition,data}.json
 * ├── FlowStatusAction-{metadata,definition,data}.json
 * ├── FlowStatusTransition-{metadata,definition,data}.json
 * └── FlowAssignment-{metadata,definition,data}.json
 */
@Service
public class ThubDataServiceImpl implements ThubDataService {

    private static final Logger log = LoggerFactory.getLogger(ThubDataServiceImpl.class);

    static final String THUB_DIR = "THUB";

    private final ObjectMapper objectMapper;

    public ThubDataServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== Read Operations ====================

    @Override
    public Map<String, ThubFlowType> readFlowTypes(Path basePath) {
        return readDataFile(basePath, "FlowType", ThubFlowType.class);
    }

    @Override
    public Map<String, ThubFlowStatus> readFlowStatuses(Path basePath) {
        return readDataFile(basePath, "FlowStatus", ThubFlowStatus.class);
    }

    @Override
    public Map<String, ThubFlowStatusAction> readFlowStatusActions(Path basePath) {
        return readDataFile(basePath, "FlowStatusAction", ThubFlowStatusAction.class);
    }

    @Override
    public Map<String, ThubFlowStatusTransition> readFlowStatusTransitions(Path basePath) {
        return readDataFile(basePath, "FlowStatusTransition", ThubFlowStatusTransition.class);
    }

    @Override
    public Map<String, ThubFlowAssignment> readFlowAssignments(Path basePath) {
        return readDataFile(basePath, "FlowAssignment", ThubFlowAssignment.class);
    }

    // ==================== Write Operations ====================

    @Override
    public void writeFlowTypes(Path basePath, Map<String, ThubFlowType> data) {
        writeDataFile(basePath, "FlowType", data);
    }

    @Override
    public void writeFlowStatuses(Path basePath, Map<String, ThubFlowStatus> data) {
        writeDataFile(basePath, "FlowStatus", data);
    }

    @Override
    public void writeFlowStatusActions(Path basePath, Map<String, ThubFlowStatusAction> data) {
        writeDataFile(basePath, "FlowStatusAction", data);
    }

    @Override
    public void writeFlowStatusTransitions(Path basePath, Map<String, ThubFlowStatusTransition> data) {
        writeDataFile(basePath, "FlowStatusTransition", data);
    }

    @Override
    public void writeFlowAssignments(Path basePath, Map<String, ThubFlowAssignment> data) {
        writeDataFile(basePath, "FlowAssignment", data);
    }

    // ==================== Private Helpers ====================

    private <T> Map<String, T> readDataFile(Path basePath, String tableName, Class<T> valueType) {
        Path dataFile = basePath.resolve(THUB_DIR).resolve(tableName + "-data.json");
        if (!Files.exists(dataFile)) {
            return new LinkedHashMap<>();
        }

        try {
            // DataFileWrapper<T> has Map<String, T> dataEntities
            // So T = valueType (e.g., ThubFlowType)
            JavaType wrapperType = objectMapper.getTypeFactory()
                    .constructParametricType(DataFileWrapper.class, valueType);

            DataFileWrapper<T> wrapper = objectMapper.readValue(dataFile.toFile(), wrapperType);
            return wrapper.dataEntities() != null
                    ? new LinkedHashMap<>(wrapper.dataEntities())
                    : new LinkedHashMap<>();
        } catch (Exception e) {
            throw new FlowStorageException("Failed to read THUB data file: " + tableName, e);
        }
    }

    private <T> void writeDataFile(Path basePath, String tableName, Map<String, T> data) {
        Path thubDir = basePath.resolve(THUB_DIR);
        try {
            if (!Files.exists(thubDir)) {
                Files.createDirectories(thubDir);
            }
            Path dataFile = thubDir.resolve(tableName + "-data.json");
            DataFileWrapper<T> wrapper = new DataFileWrapper<>(new TreeMap<>(data != null ? data : Map.of()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), wrapper);
        } catch (IOException e) {
            throw new FlowStorageException("Failed to write THUB data file: " + tableName, e);
        }
    }

    /**
     * Wrapper for the configurator data file format: { "dataEntities": { ... } }
     */
    record DataFileWrapper<T>(Map<String, T> dataEntities) {
        DataFileWrapper {
            if (dataEntities == null) {
                dataEntities = new LinkedHashMap<>();
            }
        }
    }
}
