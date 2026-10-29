import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Reconstructed example: not code from the incident described in the article. */
public final class RequirementGapDemo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DB = "jdbc:h2:mem:requirement_gap;DB_CLOSE_DELAY=-1";

    public record ImageProjection(String id, String url) {}

    private static void save(String id, JsonNode image) throws Exception {
        try (var connection = DriverManager.getConnection(DB);
             var statement = connection.prepareStatement(
                 "MERGE INTO stored_image (id, image_json) KEY (id) VALUES (?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, JSON.writeValueAsString(image));
            statement.executeUpdate();
        }
    }

    private static JsonNode read(String id) throws Exception {
        try (var connection = DriverManager.getConnection(DB);
             var statement = connection.prepareStatement(
                 "SELECT image_json FROM stored_image WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("row was not stored");
                return JSON.readTree(rows.getString(1));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("fixture path required");
        JsonNode expected = JSON.readTree(Files.readString(Path.of(args[0]))).required("image");
        if (!expected.isObject()) throw new IllegalArgumentException("image must be an object");
        String id = expected.required("id").asText();

        try (var connection = DriverManager.getConnection(DB);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stored_image (id VARCHAR(80) PRIMARY KEY, image_json CLOB NOT NULL)");
        }

        // Faulty interpretation: only id and url are kept.
        var projection = new ImageProjection(id, expected.required("url").asText());
        save(id, JSON.valueToTree(projection));
        JsonNode fromDb = read(id); // a new JDBC connection, not the saved Java object
        if (!id.equals(fromDb.required("id").asText())
                || !projection.url().equals(fromDb.required("url").asText())) {
            throw new AssertionError("projection check failed");
        }
        System.out.println("projection-only check: PASS");
        if (expected.equals(fromDb)) throw new AssertionError("fixture should expose the omission");
        System.out.println("independent fixture rejects projection: PASS");

        // This demo contract preserves every JSON member, including future members.
        save(id, expected);
        if (!expected.equals(read(id))) throw new AssertionError("full image was not read back");
        System.out.println("full image, saved and read through H2: PASS");

        ObjectNode missingVariants = (ObjectNode) expected.deepCopy();
        missingVariants.remove("variants");
        save(id, missingVariants);
        if (expected.equals(read(id))) throw new AssertionError("missing variants went unnoticed");
        System.out.println("removing variants is detected: PASS");
    }
}
