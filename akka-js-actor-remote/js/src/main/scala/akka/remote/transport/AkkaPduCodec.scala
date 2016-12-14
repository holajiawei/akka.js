/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.transport

import akka.AkkaException
import akka.actor.{ AddressFromURIString, InternalActorRef, Address, ActorRef }
import akka.remote.WireFormats._
import akka.remote._
import akka.util.ByteString
import akka.protobuf.InvalidProtocolBufferException
import akka.protobuf.{ ByteString ⇒ PByteString }
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class PduCodecException(msg: String, cause: Throwable) extends AkkaException(msg, cause)

/**
 * INTERNAL API
 *
 * Companion object of the [[akka.remote.transport.AkkaPduCodec]] trait. Contains the representation case classes
 * of decoded Akka Protocol Data Units (PDUs).
 */
private[remote] object AkkaPduCodec {

  /**
   * Trait that represents decoded Akka PDUs (Protocol Data Units)
   */
  sealed trait AkkaPdu
  final case class Associate(info: HandshakeInfo) extends AkkaPdu
  final case class Disassociate(reason: AssociationHandle.DisassociateInfo) extends AkkaPdu
  case object Heartbeat extends AkkaPdu
  final case class Payload(bytes: ByteString) extends AkkaPdu

  final case class Message(
    recipient:         InternalActorRef,
    recipientAddress:  Address,
    serializedMessage: SerializedMessage,
    senderOption:      OptionVal[ActorRef],
    seqOption:         Option[SeqNo]) extends HasSequenceNumber {

    def reliableDeliveryEnabled = seqOption.isDefined

    override def seq: SeqNo = seqOption.get
  }
}

/**
 * INTERNAL API
 *
 * A Codec that is able to convert Akka PDUs (Protocol Data Units) from and to [[akka.util.ByteString]]s.
 */
private[remote] trait AkkaPduCodec {
  import AkkaPduCodec._
  /**
   * Returns an [[akka.remote.transport.AkkaPduCodec.AkkaPdu]] instance that represents the PDU contained in the raw
   * ByteString.
   * @param raw
   *   Encoded raw byte representation of an Akka PDU
   * @return
   *   Case class representation of the decoded PDU that can be used in a match statement
   */
  def decodePdu(raw: ByteString): AkkaPdu

  /**
   * Takes an [[akka.remote.transport.AkkaPduCodec.AkkaPdu]] representation of an Akka PDU and returns its encoded
   * form as a [[akka.util.ByteString]].
   *
   * For the same effect the constructXXX methods might be called directly, taking method parameters instead of the
   * [[akka.remote.transport.AkkaPduCodec.AkkaPdu]] final case classes.
   *
   * @param pdu
   *   The Akka Protocol Data Unit to be encoded
   * @return
   *   Encoded form as raw bytes
   */
  def encodePdu(pdu: AkkaPdu): ByteString = pdu match {
    case Associate(info)      ⇒ constructAssociate(info)
    case Payload(bytes)       ⇒ constructPayload(bytes)
    case Disassociate(reason) ⇒ constructDisassociate(reason)
    case Heartbeat            ⇒ constructHeartbeat
  }

  def constructPayload(payload: ByteString): ByteString

  def constructAssociate(info: HandshakeInfo): ByteString

  def constructDisassociate(reason: AssociationHandle.DisassociateInfo): ByteString

  def constructHeartbeat: ByteString

  def decodeMessage(raw: ByteString, provider: RemoteActorRefProvider, localAddress: Address): (Option[Ack], Option[Message])

  def constructMessage(
    localAddress:      Address,
    recipient:         ActorRef,
    serializedMessage: SerializedMessage,
    senderOption:      OptionVal[ActorRef],
    seqOption:         Option[SeqNo]       = None,
    ackOption:         Option[Ack]         = None): ByteString

  def constructPureAck(ack: Ack): ByteString
}

/**
 * INTERNAL API
 */
private[remote] object AkkaPduProtobufCodec extends AkkaPduCodec {
  import AkkaPduCodec._

  private def ackBuilder(ack: Ack): AcknowledgementInfo = {
    AcknowledgementInfo(
      cumulativeAck = ack.cumulativeAck.rawValue,
      nacks = ack.nacks.map(_.rawValue).toSeq
    )
  }

  override def constructMessage(
    localAddress:      Address,
    recipient:         ActorRef,
    serializedMessage: SerializedMessage,
    senderOption:      OptionVal[ActorRef],
    seqOption:         Option[SeqNo]       = None,
    ackOption:         Option[Ack]         = None): ByteString = {

    val sender =
      senderOption match {
        case OptionVal.Some(sender) ⇒ Some(serializeActorRef(localAddress, sender))
        case OptionVal.None         ⇒ None
      }

    val anckAndEnvelop =
      AckAndEnvelopeContainer(
        ack = ackOption.map(ackBuilder),
        envelope = Some(RemoteEnvelope(
            recipient = serializeActorRef(recipient.path.address, recipient),
            sender = sender,
            message = serializedMessage,
            seq = seqOption.map(_.rawValue)
          ))
      )

    ByteString.ByteString1C(anckAndEnvelop.toByteArray)
  }

  override def constructPureAck(ack: Ack): ByteString =
    ByteString.ByteString1C(
      AckAndEnvelopeContainer(
        ack = Some(ackBuilder(ack)),
        envelope = None
      ).toByteArray
    ) //Reuse Byte Array (naughty!)

  override def constructPayload(payload: ByteString): ByteString =
    ByteString.ByteString1C(
      AkkaProtocolMessage(
        payload = Some(PByteString.copyFrom(payload.asByteBuffer.array()))
      ).toByteArray
    ) //Reuse Byte Array (naughty!)

  override def constructAssociate(info: HandshakeInfo): ByteString = {
    val handshakeInfo = AkkaHandshakeInfo(
      origin = serializeAddress(info.origin),
      uid = info.uid.toLong,
      cookie = info.cookie
    )
    constructControlMessagePdu(WireFormats.CommandType.ASSOCIATE, Some(handshakeInfo))
  }

  private val DISASSOCIATE = constructControlMessagePdu(WireFormats.CommandType.DISASSOCIATE, None)
  private val DISASSOCIATE_SHUTTING_DOWN = constructControlMessagePdu(WireFormats.CommandType.DISASSOCIATE_SHUTTING_DOWN, None)
  private val DISASSOCIATE_QUARANTINED = constructControlMessagePdu(WireFormats.CommandType.DISASSOCIATE_QUARANTINED, None)

  override def constructDisassociate(info: AssociationHandle.DisassociateInfo): ByteString = info match {
    case AssociationHandle.Unknown     ⇒ DISASSOCIATE
    case AssociationHandle.Shutdown    ⇒ DISASSOCIATE_SHUTTING_DOWN
    case AssociationHandle.Quarantined ⇒ DISASSOCIATE_QUARANTINED
  }

  override val constructHeartbeat: ByteString =
    constructControlMessagePdu(WireFormats.CommandType.HEARTBEAT, None)

  override def decodePdu(raw: ByteString): AkkaPdu = {
    try {
      val pdu = AkkaProtocolMessage.parseFrom(raw.toArray)
      if (pdu.payload.isDefined) Payload(ByteString(pdu.getPayload.asReadOnlyByteBuffer()))
      else if (pdu.instruction.isDefined) decodeControlPdu(pdu.getInstruction)
      else throw new PduCodecException("Error decoding Akka PDU: Neither message nor control message were contained", null)
    } catch {
      case e: InvalidProtocolBufferException ⇒ throw new PduCodecException("Decoding PDU failed.", e)
    }
  }

  override def decodeMessage(
    raw:          ByteString,
    provider:     RemoteActorRefProvider,
    localAddress: Address): (Option[Ack], Option[Message]) = {
    val ackAndEnvelope = AckAndEnvelopeContainer.parseFrom(raw.toArray)

    val ackOption = if (ackAndEnvelope.ack.isDefined) {
      import scala.collection.JavaConverters._
      Some(Ack(SeqNo(ackAndEnvelope.getAck.cumulativeAck), ackAndEnvelope.getAck.nacks.map(SeqNo(_)).toSet))
    } else None

    val messageOption = if (ackAndEnvelope.envelope.isDefined) {
      val msgPdu = ackAndEnvelope.getEnvelope
      Some(Message(
        recipient = provider.resolveActorRefWithLocalAddress(msgPdu.recipient.path, localAddress),
        recipientAddress = AddressFromURIString(msgPdu.recipient.path),
        serializedMessage = msgPdu.message,
        senderOption =
          if (msgPdu.sender.isDefined) OptionVal(provider.resolveActorRefWithLocalAddress(msgPdu.sender.get.path, localAddress))
          else OptionVal.None,
        seqOption =
          if (msgPdu.seq.isDefined) Some(SeqNo(msgPdu.seq.get)) else None))
    } else None

    (ackOption, messageOption)
  }

  private def decodeControlPdu(controlPdu: AkkaControlMessage): AkkaPdu = {

    controlPdu.commandType match {
      case CommandType.ASSOCIATE if controlPdu.handshakeInfo.isDefined ⇒
        val handshakeInfo = controlPdu.handshakeInfo.get
        val cookie = handshakeInfo.cookie
        Associate(
          HandshakeInfo(
            decodeAddress(handshakeInfo.origin),
            handshakeInfo.uid.toInt, // 64 bits are allocated in the wire formats, but we use only 32 for now
            cookie))
      case CommandType.DISASSOCIATE               ⇒ Disassociate(AssociationHandle.Unknown)
      case CommandType.DISASSOCIATE_SHUTTING_DOWN ⇒ Disassociate(AssociationHandle.Shutdown)
      case CommandType.DISASSOCIATE_QUARANTINED   ⇒ Disassociate(AssociationHandle.Quarantined)
      case CommandType.HEARTBEAT                  ⇒ Heartbeat
      case x ⇒
        throw new PduCodecException(s"Decoding of control PDU failed, invalid format, unexpected: [${x}]", null)
    }
  }

  private def decodeAddress(encodedAddress: AddressData): Address =
    Address(
      protocol = encodedAddress.protocol.get,
      system = encodedAddress.system,
      host = encodedAddress.hostname,
      port = encodedAddress.port
    )

  private def constructControlMessagePdu(
    code:          WireFormats.CommandType,
    handshakeInfo: Option[AkkaHandshakeInfo]): ByteString = {

    val controlMessageBuilder = AkkaControlMessage(
      commandType = code,
      handshakeInfo = handshakeInfo
    )

    ByteString.ByteString1C(AkkaProtocolMessage(
      instruction = Some(controlMessageBuilder)
    ).toByteArray) //Reuse Byte Array (naughty!)
  }

  private def serializeActorRef(defaultAddress: Address, ref: ActorRef): ActorRefData = {
    ActorRefData(
      path =
        if (ref.path.address.host.isDefined) ref.path.toSerializationFormat else ref.path.toSerializationFormatWithAddress(defaultAddress)
    )
  }

  private def serializeAddress(address: Address): AddressData = address match {
    case Address(protocol, system, Some(host), Some(port)) ⇒
      AddressData(
        hostname = host,
        port = port,
        system = system,
        protocol = Some(protocol)
      )
    case _ ⇒ throw new IllegalArgumentException(s"Address [${address}] could not be serialized: host or port missing.")
  }

}
