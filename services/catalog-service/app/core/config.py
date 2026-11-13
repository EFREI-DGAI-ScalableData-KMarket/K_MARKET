from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    mongo_uri: str
    mongo_db: str
    jwt_secret: str

    class Config:
        env_file = ".env"

settings = Settings()