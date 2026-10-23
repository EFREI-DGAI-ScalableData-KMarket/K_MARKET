
const dbm = db.getSiblingDB("kmarket");


dbm.createCollection("catalog", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["product_id", "name", "category", "price_cents"],
      properties: {
        _id:         { bsonType: "objectId" },
        product_id:  { bsonType: ["int", "long"], description: "Référence logique reprise par order_items.product_id et stock.product_id (PostgreSQL)" },
        name:        { bsonType: "string", minLength: 1 },
        category:    { bsonType: "string", enum: ["LighStick", "Book", "CD", "Album"] ,minLength: 1 },
        price_cents: { bsonType: ["int", "long"], minimum: 0, description: "Prix en centimes" },
        status:      { enum: ["active", "inactive"], description: "Optionnel (présent dans le volet précédent)" },
        tags:        { bsonType: "array", items: { bsonType: "string" } },
        description: { bsonType: "string" },
        attributes:  { bsonType: "object", description: "Structure variable selon la catégorie" },
        indexed_at:  { bsonType: "date" }
      }
    }
  },
  validationLevel: "strict",
  validationAction: "error"
});
