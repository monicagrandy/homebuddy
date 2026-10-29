from datetime import datetime
from typing import Optional

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Index, Integer, String, Text, UniqueConstraint, false, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from backend.config import settings
from backend.db import Base
from backend.encryption import EncryptedBytes

try:
    from pgvector.sqlalchemy import Vector
except Exception:  
    Vector = None


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    cognito_sub: Mapped[str] = mapped_column(String(255), unique=True, nullable=False)
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False)
    display_name: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    memberships: Mapped[list["HouseholdMember"]] = relationship(back_populates="user")
    invitations_sent: Mapped[list["HouseholdInvitation"]] = relationship(
        back_populates="invited_by",
        foreign_keys="HouseholdInvitation.invited_by_user_id",
    )


class Household(Base):
    __tablename__ = "households"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(50), nullable=False)
    zip_code: Mapped[str] = mapped_column(String)
    home_type: Mapped[str] = mapped_column(String)
    documents: Mapped[list["Document"]] = relationship(back_populates="household", passive_deletes=True)
    cases: Mapped[list["Case"]] = relationship(back_populates="household", passive_deletes=True)
    maintenance_tasks: Mapped[list["MaintenanceTask"]] = relationship(back_populates="household", passive_deletes=True)
    assets: Mapped[list["Asset"]] = relationship(back_populates="household", passive_deletes=True)
    members: Mapped[list["HouseholdMember"]] = relationship(back_populates="household", passive_deletes=True)
    invitations: Mapped[list["HouseholdInvitation"]] = relationship(back_populates="household", passive_deletes=True)


class HouseholdMember(Base):
    __tablename__ = "household_members"
    __table_args__ = (UniqueConstraint("household_id", "user_id", name="uq_household_user"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"), nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    role: Mapped[str] = mapped_column(String(20), default="member")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    household: Mapped["Household"] = relationship(back_populates="members")
    user: Mapped["User"] = relationship(back_populates="memberships")


class HouseholdInvitation(Base):
    __tablename__ = "household_invitations"
    __table_args__ = (UniqueConstraint("token", name="uq_household_invite_token"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"), nullable=False)
    email: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(20), default="member")
    status: Mapped[str] = mapped_column(String(20), default="pending")
    token: Mapped[str] = mapped_column(String(64), nullable=False)
    invited_by_user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    accepted_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    household: Mapped["Household"] = relationship(back_populates="invitations")
    invited_by: Mapped["User"] = relationship(back_populates="invitations_sent")


class Asset(Base):
    __tablename__ = "assets"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(String(50), nullable=False)
    brand: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    model_number: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    room: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    install_date: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    warranty_end_date: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    household: Mapped["Household"] = relationship(back_populates="assets")
    documents: Mapped[list["Document"]] = relationship(back_populates="asset", passive_deletes=True)
    cases: Mapped[list["Case"]] = relationship(back_populates="asset", passive_deletes=True)
    maintenance_tasks: Mapped[list["MaintenanceTask"]] = relationship(back_populates="asset", passive_deletes=True)


class Document(Base):
    __tablename__ = "documents"
    __table_args__ = (
        UniqueConstraint("household_id", "entry_id", name="uq_document_household_entry"),
        Index("ix_documents_household_sha256", "household_id", "content_sha256"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    entry_id: Mapped[str] = mapped_column(String)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"))
    asset_id: Mapped[Optional[int]] = mapped_column(ForeignKey("assets.id", ondelete="SET NULL"), nullable=True)
    doc_type: Mapped[str] = mapped_column(String)
    source_name: Mapped[str] = mapped_column(String)
    source_url: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    # Encrypted at rest when DOCUMENT_ENCRYPTION_KEY is set (backend/encryption.py).
    file_bytes: Mapped[Optional[bytes]] = mapped_column(EncryptedBytes, nullable=True)
    uploaded_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        server_default=func.now(),
    )
    storage_key: Mapped[Optional[str]] = mapped_column(String, nullable = True)
    # SHA-256 of the uploaded file, so the same PDF added from two devices isn't stored twice.
    content_sha256: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    uploaded_by_user_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )
    # "indexing" while a background job builds its chunks, then "ready".
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ready", server_default="ready")
    household: Mapped["Household"] = relationship(back_populates="documents")
    asset: Mapped["Asset"] = relationship(back_populates="documents")


class IngestionJob(Base):
    """A background run that indexes one document; clients poll it after uploading."""

    __tablename__ = "ingestion_jobs"
    __table_args__ = (
        UniqueConstraint("user_id", "idempotency_key", name="uq_ingestion_job_idempotency"),
        Index("ix_ingestion_jobs_status_run_after", "status", "run_after"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"), nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    entry_id: Mapped[str] = mapped_column(String, nullable=False)
    # Whether this job created the document (a failed new upload is removed again).
    created_document: Mapped[bool] = mapped_column(nullable=False, default=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="queued")
    error_code: Mapped[Optional[str]] = mapped_column(String(40), nullable=True)
    error: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    chunks_indexed: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    idempotency_key: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    run_after: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
    )
    finished_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class DocumentChunk(Base):
    __tablename__ = "document_chunks"
    __table_args__ = (
        UniqueConstraint("entry_id", "household_id", "chunk_index", name="uq_document_chunk_entry_household_index"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"), nullable=False)
    entry_id: Mapped[str] = mapped_column(String, nullable=False)
    session_id: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    asset_id: Mapped[Optional[int]] = mapped_column(ForeignKey("assets.id", ondelete="SET NULL"), nullable=True)
    doc_type: Mapped[str] = mapped_column(String, nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    source: Mapped[str] = mapped_column(String, nullable=False)
    page: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    if Vector is not None:
        embedding: Mapped[Optional[list[float]]] = mapped_column(Vector(settings.embedding_dimensions), nullable=True)
    else:
        embedding: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Case(Base):
    __tablename__ = "cases"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"))
    asset_id: Mapped[Optional[int]] = mapped_column(ForeignKey("assets.id", ondelete="SET NULL"), nullable=True)
    title: Mapped[str] = mapped_column(String)
    summary: Mapped[str] = mapped_column(String)
    severity: Mapped[str] = mapped_column(String)
    status: Mapped[str] = mapped_column(String)
    contractor_trade: Mapped[str] = mapped_column(String)
    household: Mapped["Household"] = relationship(back_populates="cases")
    asset: Mapped["Asset"] = relationship(back_populates="cases")


class MaintenanceTask(Base):
    __tablename__ = "maintenance_tasks"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"))
    asset_id: Mapped[Optional[int]] = mapped_column(ForeignKey("assets.id", ondelete="SET NULL"), nullable=True)
    title: Mapped[str] = mapped_column(String)
    due_date: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[str] = mapped_column(String)
    notes: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    household: Mapped["Household"] = relationship(back_populates="maintenance_tasks")
    asset: Mapped["Asset"] = relationship(back_populates="maintenance_tasks")


class Conversation(Base):
    """A chat thread. Private to the user who started it; documents stay shared with the household."""

    __tablename__ = "conversations"
    __table_args__ = (
        UniqueConstraint("household_id", "session_id", name="uq_conversation_household_session"),
        Index("ix_conversations_user_updated", "user_id", "updated_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"), nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # The id clients use (generated by the client when a thread starts).
    session_id: Mapped[str] = mapped_column(String(255), nullable=False)
    title: Mapped[Optional[str]] = mapped_column(String(120), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    # Last activity; drives sync (updated_since) and 90-day retention.
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    # Set while a question is being answered, so a second device can't interleave another one.
    active_query_started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class ConversationMessage(Base):
    __tablename__ = "conversation_messages"
    __table_args__ = (Index("ix_conversation_messages_conversation", "conversation_id", "id"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    conversation_id: Mapped[int] = mapped_column(
        ForeignKey("conversations.id", ondelete="CASCADE"),
        nullable=False,
    )
    household_id: Mapped[int] = mapped_column(ForeignKey("households.id", ondelete="CASCADE"), nullable=False)
    session_id: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str] = mapped_column(String, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    # Answers only: the citations and urgency the live answer showed, so history shows them too.
    sources: Mapped[Optional[list]] = mapped_column(JSON, nullable=True)
    urgency_level: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    should_escalate: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, server_default=false())


class ContractorSearchUsage(Base):
    __tablename__ = "contractor_search_usage"
    __table_args__ = (
        UniqueConstraint("user_id", "period_start", name="uq_contractor_search_usage_user_period"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    period_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    searches_used: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
    )
