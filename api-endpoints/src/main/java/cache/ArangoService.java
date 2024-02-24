package cache;

import com.arangodb.ArangoDB;
import com.arangodb.ArangoDBException;
import com.arangodb.entity.ArangoDBVersion;

import com.arangodb.entity.CollectionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    public void createDocument(String key){
        /*
        BaseDocument myObject = new BaseDocument();
        myObject.setKey("myKey");
        myObject.addAttribute("a", "Foo");
        myObject.addAttribute("b", 42);
        try {
          arangoDB.db(dbName).collection(collectionName).insertDocument(myObject);
          System.out.println("Document created");
        } catch(ArangoDBException e) {
          System.err.println("Failed to create document. " + e.getMessage());
        }
        * */

    }

    public void updateDocument(){
        /*

        myObject.addAttribute("c", "Bar");
        try {
          arangoDB.db(dbName).collection(collectionName).updateDocument("myKey", myObject);
        } catch (ArangoDBException e) {
          System.err.println("Failed to update document. " + e.getMessage());
        }

        * */
    }

}
