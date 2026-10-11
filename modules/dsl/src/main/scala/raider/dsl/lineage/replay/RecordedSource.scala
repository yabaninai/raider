package raider.dsl.lineage.replay

import raider.dsl.lineage.data.{LineageDataDocument, NodeData}
import raider.dsl.lineage.store.{LineageDataStore, StoreError}

import java.nio.file.Path

/** Typed failure of recorded-data access for the replay interpreter (LIN-05D,
  * ADR-027 §2–3):
  *   - [[ReplayDataError.Missing]] — no data entry exists under the requested
  *     DataRef;
  *   - [[ReplayDataError.KindMismatch]] — the DataRef exists but points at a
  *     non-`ModelCallData` entry (phase/tool/delegation data); `expected` is
  *     the only kind [[RecordedSource.modelOutput]] returns, `actual` is the
  *     NodeData variant name found under the ref.
  *
  * Deliberately minimal — pure lookup facts over the frozen lineage-data
  * document; no schema/wire extensions, no auto-repair, no default answers.
  */
enum ReplayDataError:
  case Missing(dataRef: String)
  case KindMismatch(dataRef: String, expected: String, actual: String)

/** Read-only access to the recorded model answers of one lineage-data document
  * (LIN-05D — the data-access half of the LIN-05 replay interpreter, ADR-027
  * §2–3: replay-recorded takes LLM texts from the data resource, never from
  * code, and never re-asks the model).
  *
  * Lookup is EXACT by DataRef against the document's `data` map: no prefix
  * matching, no fallback to another entry, no best-effort guessing — the replay
  * chain must reproduce the recorded run, so a ref that cannot be resolved as
  * recorded model output is a typed failure, never a substitute.
  */
final class RecordedSource private (document: LineageDataDocument):

  /** The recorded model call under `dataRef`, or a typed failure:
    * [[ReplayDataError.Missing]] when the ref is unknown,
    * [[ReplayDataError.KindMismatch]] when the ref resolves to non-model data
    * (a phase/tool/delegation entry).
    */
  def modelOutput(
      dataRef: String
  ): Either[ReplayDataError, NodeData.ModelCallData] =
    document.data.get(dataRef) match
      case None => Left(ReplayDataError.Missing(dataRef))
      case Some(data: NodeData.ModelCallData) => Right(data)
      case Some(other) =>
        Left(
          ReplayDataError.KindMismatch(
            dataRef,
            RecordedSource.ModelCallKind,
            RecordedSource.kindName(other)
          )
        )

  /** Whether any data entry exists under `dataRef` (of any kind). */
  def has(dataRef: String): Boolean = document.data.contains(dataRef)

  /** Every DataRef of the document in deterministic order (sorted — the same
    * canonical order the codec uses for the `data` object on the wire, so two
    * sources over equal documents always expose equal `dataRefs`).
    */
  def dataRefs: Vector[String] = document.data.keys.toVector.sorted

object RecordedSource:

  private val ModelCallKind = "ModelCallData"

  /** Wraps an in-memory lineage-data document (e.g. decoded elsewhere or built
    * by the wave-2 event→document reducer).
    */
  def apply(doc: LineageDataDocument): RecordedSource =
    new RecordedSource(doc)

  /** Loads the document through the recording store
    * ([[LineageDataStore.read]]): store failures pass through typed and
    * untouched — `StoreError.Missing` for an absent file, `StoreError.Corrupt`
    * for undecodable content, `StoreError.Io` for environment failures. Nothing
    * is masked into a success or a replay error; content validation beyond
    * decode stays in the data layer.
    */
  def fromFile(path: Path): Either[StoreError, RecordedSource] =
    LineageDataStore.read(path).map(new RecordedSource(_))

  // NodeData variant names (the data layer has no wire discriminator for
  // `data` entries, so the variant name is the honest "actual kind" label).
  private[replay] def kindName(data: NodeData): String = data match
    case _: NodeData.ToolCallData   => "ToolCallData"
    case _: NodeData.DelegationData => "DelegationData"
    case _: NodeData.PhaseData      => "PhaseData"
    case _: NodeData.ModelCallData  => ModelCallKind

end RecordedSource
