"""Erreurs GraphQL du catalogue : message + extensions.code (voir enum ErrorCode du contrat)."""
from graphql import GraphQLError


def gql_error(code: str, message: str, **details) -> GraphQLError:
    extensions = {"code": code}
    if details:
        extensions["details"] = details
    return GraphQLError(message, extensions=extensions)
