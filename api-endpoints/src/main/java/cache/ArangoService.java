package cache;

import com.arangodb.ArangoDB;
import com.arangodb.ArangoDBException;
import com.arangodb.entity.ArangoDBVersion;
import com.arangodb.entity.BaseDocument;

import com.arangodb.entity.CollectionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;

@ApplicationScoped
public class ArangoService {

    private final ArangoDB arango;
    private final String dbName = "idekanin";
    private final String dbCollection = "id_request_cache";
    private CollectionEntity myArangoCollection;

    @Inject
    public ArangoService(final ArangoDB arango) {
        this.arango = arango;

        try {
            // Check if the database exists, if not create it.
            if (!arango.getAccessibleDatabases().contains(dbName)){
                arango.createDatabase(dbName);
                System.out.println("Database created: " + dbName);
            } else {
                System.out.println("Using existing database: " + dbName);
            }

            // Check if the collection exists, if not create it.
            if (!arango.db(dbName).getCollections().contains(dbCollection)){
                myArangoCollection = arango.db(dbName).createCollection(dbCollection);
                System.out.println("Collection created: " + dbCollection);
            } else {
                System.out.println("Using existing collection: " + dbCollection);
            }
        } catch(ArangoDBException e) {
            System.err.println("Failed to create database: " + dbName + " or collection: " + dbCollection + "; " + e.getMessage());
        }
    }

    public ArangoDBVersion getVersion() {
        return arango.getVersion();
    }

    /**
     * Records a freshly accepted lookup request in the cache with status "pending",
     * keyed by the request hash, so the results endpoint can answer polls from the
     * moment the request is accepted. If a document already exists for the hash,
     * it is left as-is: repeated lookups are idempotent and the cached state stands.
     */
    public void recordPendingRequest(LookupQueueRequest request) {
        String key = request.requestHash.toString();
        try {
            if (arango.db(dbName).collection(dbCollection).documentExists(key)) {
                System.out.println("Request already recorded, leaving cache document as-is: " + key);
                return;
            }
            BaseDocument doc = new BaseDocument(key);
            doc.addAttribute("requestID", request.requestID.toString());
            doc.addAttribute("id", request.id);
            doc.addAttribute("context", request.context);
            doc.addAttribute("status", "pending");
            arango.db(dbName).collection(dbCollection).insertDocument(doc);
            System.out.println("Recorded pending request: " + key);
        } catch (ArangoDBException e) {
            System.err.println("Failed to record pending request: " + key + "; " + e.getMessage());
        }
    }

    /**
     * Reads the cache document for a lookup request by its request hash.
     * Returns null if no document exists for the key.
     */
    public BaseDocument getRequestDocument(String key) {
        try {
            return arango.db(dbName).collection(dbCollection).getDocument(key, BaseDocument.class);
        } catch (ArangoDBException e) {
            System.err.println("Failed to read request document: " + key + "; " + e.getMessage());
            return null;
        }
    }

}
