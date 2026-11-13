const dbName = process.env.MONGO_INITDB_DATABASE || "kmarket";
const dbm = db.getSiblingDB(dbName);

const CATEGORIES = ["Album", "Lightstick", "Merch", "Drama", "Livre", "Beaute"];

const byCategory = (category, required, properties) => ({
  properties: {
    category: { enum: [category] },
    attributes: { bsonType: "object", required, properties }
  }
});

const str = { bsonType: "string", minLength: 1 };
const int = (min) => ({ bsonType: ["int", "long"], minimum: min });
const strArray = { bsonType: "array", items: { bsonType: "string" } };

dbm.createCollection("catalog", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["product_id", "name", "category", "price_cents", "status", "attributes"],
      properties: {
        _id:         { bsonType: "objectId" },
        product_id:  { bsonType: ["int", "long"], minimum: 1,
                       description: "Clé applicative, reprise par order_items.product_id et stock.product_id (PostgreSQL)" },
        name:        str,
        category:    { enum: CATEGORIES },
        price_cents: { bsonType: ["int", "long"], minimum: 0, description: "Prix en centimes" },
        status:      { enum: ["active", "inactive"], description: "inactive = produit archivé" },
        tags:        strArray,
        description: { bsonType: "string" },
        attributes:  { bsonType: "object", description: "Structure propre à chaque catégorie" },
        indexed_at:  { bsonType: "date" }
      },
      oneOf: [
        byCategory("Album", ["artist", "version", "type", "release_date"], {
          artist: str, version: str,
          type: { enum: ["studio", "mini", "single", "mixtape", "anthology"] },
          release_date: str, photocards: int(0), limited_edition: { bsonType: "bool" }
        }),
        byCategory("Lightstick", ["artist", "version"], {
          artist: str, version: str, bluetooth: { bsonType: "bool" }, piles: str
        }),
        byCategory("Merch", ["artist", "type"], {
          artist: str, type: str, size: strArray, couleur: strArray, matiere: str
        }),
        byCategory("Drama", ["serie", "support"], {
          serie: str, saison: int(1), episodes: int(1),
          support: { enum: ["DVD", "Blu-ray", "Coffret", "Artbook"] }, sous_titres: strArray
        }),
        byCategory("Livre", ["isbn", "langue"], {
          isbn: { bsonType: "string", pattern: "^97[89][0-9]{10}$" },
          langue: { enum: ["fr", "en", "ko"] }, auteur: str, pages: int(1), genre: str
        }),
        byCategory("Beaute", ["marque", "contenance_ml"], {
          marque: str, type: str, contenance_ml: int(1), type_peau: str, ingredients: strArray
        })
      ]
    }
  },
  validationLevel: "strict",
  validationAction: "error"
});


dbm.catalog.createIndex({ product_id: 1 }, { unique: true, name: "uq_product_id" });
dbm.catalog.createIndex({ name: "text", description: "text" },
                        { name: "txt_name_description", default_language: "french",
                          weights: { name: 10, description: 1 } });
dbm.catalog.createIndex({ category: 1, status: 1 }, { name: "idx_category_status" });


// Compteur de product_id : l'application obtient le suivant avec
//   findOneAndUpdate({ _id: "product_id" }, { $inc: { seq: 1 } }, { returnDocument: "after" })
// L'index unique ci-dessus reste le garde-fou en cas de collision.
dbm.counters.updateOne({ _id: "product_id" }, { $setOnInsert: { seq: 0 } }, { upsert: true });


// Utilisateur du service Catalogue : lecture/écriture sur cette base seulement, pas root.

const pwd = process.env.CATALOG_DB_PASSWORD;
if (!pwd) {
  throw new Error("CATALOG_DB_PASSWORD manquant dans .env");
}
dbm.createUser({ user: "catalog_svc", pwd, roles: [{ role: "readWrite", db: dbName }] });

print(`Base ${dbName} : collection catalog, index et utilisateur catalog_svc créés`);
