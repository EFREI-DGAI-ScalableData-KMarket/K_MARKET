from strawberry.fastapi import GraphQLRouter
from app.schemas.catalog import schema


router = GraphQLRouter(schema)