package com.blerpc;

import static com.google.common.base.Preconditions.checkArgument;

import com.blerpc.proto.Blerpc;
import com.blerpc.proto.BytesRange;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Message;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Message converter that serialize/deserialize proto message to byte array based on byte range descriptions in annotations.
 */
public class AnnotationMessageConverter implements MessageConverter {

    private final ByteOrder byteOrder;

    /**
     * Create {@link AnnotationMessageConverter} instance for big endian byte order.
     */
    public AnnotationMessageConverter() {
        this(ByteOrder.BIG_ENDIAN);
    }

    /**
     * Create {@link AnnotationMessageConverter} instance.
     *
     * @param byteOrder - byte order that will be used while serialize/deserialize field values to bytes.
     */
    public AnnotationMessageConverter(ByteOrder byteOrder) {
        this.byteOrder = byteOrder;
    }

    @Override
    public byte[] serializeRequest(MethodDescriptor methodDescriptor, Message message) throws CouldNotConvertMessageException {
        checkHasSizeAnnotation(message);
        int messageBytesSize = getMessageBytesSize(message);
        if (messageBytesSize == 0) {
            return new byte[0];
        }
        byte[] requestBytes = new byte[messageBytesSize];
        serializeMessage(requestBytes, message, BytesRange.newBuilder()
                .setFromByte(0)
                .setToByte(messageBytesSize)
                .build());
        return requestBytes;
    }

    private void serializeMessage(byte[] requestBytes, Message message, BytesRange requestBytesRange) throws CouldNotConvertMessageException {
        validateMessageSchema(message, requestBytesRange);
        for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            FieldDescriptor fieldDescriptor = entry.getKey();
            Object fieldValue = entry.getValue();
            String fieldName = fieldDescriptor.getName();
            BytesRange relativeFieldBytesRange = getRelativeBytesRange(requestBytesRange, fieldDescriptor);
            JavaType fieldType = fieldDescriptor.getType().getJavaType();
            switch (fieldType) {
                case MESSAGE:
                    serializeMessage(requestBytes, (Message) fieldValue, relativeFieldBytesRange);
                    break;
                case INT:
                    serializeLong(requestBytes, (Integer) fieldValue, relativeFieldBytesRange, fieldName);
                    break;
                case LONG:
                    serializeLong(requestBytes, (Long) fieldValue, relativeFieldBytesRange, fieldName);
                    break;
                case ENUM:
                    serializeEnum(requestBytes, (EnumValueDescriptor) fieldValue, relativeFieldBytesRange, fieldName);
                    break;
                case BOOLEAN:
                    serializeBoolean(requestBytes, (Boolean) fieldValue, relativeFieldBytesRange, fieldName);
                    break;
                // TODO(#5): Add support of ByteString, String, Float and Double.
                default:
                    checkArgument(false, "Unsupported field type: %s, field name: %s", fieldType.name(), fieldName);
            }
        }
    }

    private void serializeLong(byte[] messageBytes, long fieldValue, BytesRange bytesRange, String fieldName) {
        int firstByte = bytesRange.getFromByte();
        int bytesCount = bytesRange.getToByte() - firstByte;
        checkArgument(bytesCount <= 8,
                "Only integer fields with declared bytes size in [1, 8] are supported. Field %s has %s bytes size.",
                fieldName,
                bytesCount);
        if (byteOrder.equals(ByteOrder.BIG_ENDIAN)) {
            for (int i = 0; i < bytesCount; i++) {
                messageBytes[firstByte + i] = (byte) (fieldValue >> 8 * (bytesCount - i - 1));
            }
        } else {
            for (int i = 0; i < bytesCount; i++) {
                messageBytes[firstByte + i] = (byte) (fieldValue >> 8 * i);
            }
        }
    }

    private void serializeBoolean(byte[] messageBytes, boolean fieldValue, BytesRange bytesRange, String fieldName) {
        int bytesSize = bytesRange.getToByte() - bytesRange.getFromByte();
        checkArgument(bytesSize == 1,
                    "Boolean value %s has bytes size = %s that more than 1 byte",
                    fieldName,
                    bytesSize);
        messageBytes[bytesRange.getFromByte()] = fieldValue ? (byte) 1 : (byte) 0;
    }

    private void serializeEnum(byte[] messageBytes, EnumValueDescriptor enumDescriptor, BytesRange bytesRange, String fieldName) {
        checkBytesRangeEnoughForEnum(enumDescriptor, bytesRange, fieldName);
        serializeLong(messageBytes, enumDescriptor.getNumber(), bytesRange, fieldName);
    }

    @Override
    public Message deserializeResponse(MethodDescriptor methodDescriptor, Message message, byte[] value) {
        if (value.length == 0) {
            return message.getDefaultInstanceForType();
        }
        checkHasSizeAnnotation(message);
        int messageBytesSize = getMessageBytesSize(message);
        checkArgument(value.length == messageBytesSize,
                "Message %s byte size %s is not equals to expected size of device response %s",
                message.getDescriptorForType().getName(),
                value.length,
                messageBytesSize);
        return deserializeMessage(message, value, BytesRange.newBuilder()
                .setFromByte(0)
                .setToByte(messageBytesSize)
                .build());
    }

    private Message deserializeMessage(Message message, byte[] value, BytesRange requestBytesRange) {
        validateMessageSchema(message, requestBytesRange);
        Message.Builder messageBuilder = message.toBuilder();
        for (FieldDescriptor fieldDescriptor : message.getDescriptorForType().getFields()) {
            BytesRange relativeFieldBytesRange = getRelativeBytesRange(requestBytesRange, fieldDescriptor);
            String fieldName = fieldDescriptor.getName();
            JavaType fieldType = fieldDescriptor.getType().getJavaType();
            switch (fieldType) {
                case MESSAGE:
                    messageBuilder.setField(fieldDescriptor, deserializeMessage((Message) message.getField(fieldDescriptor),
                            value,
                            relativeFieldBytesRange));
                    break;
                case INT:
                    messageBuilder.setField(fieldDescriptor, (int) deserializeLong(value, relativeFieldBytesRange, fieldName));
                    break;
                case LONG:
                    messageBuilder.setField(fieldDescriptor, deserializeLong(value, relativeFieldBytesRange, fieldName));
                    break;
                case ENUM:
                    messageBuilder.setField(fieldDescriptor, deserializeEnum(value, fieldDescriptor, relativeFieldBytesRange));
                    break;
                case BOOLEAN:
                    messageBuilder.setField(fieldDescriptor, deserializeBoolean(value, relativeFieldBytesRange, fieldName));
                    break;
                // TODO(#5): Add support of ByteString, String, Float and Double.
                default:
                    checkArgument(false, "Unsupported field type: %s, field name: %s", fieldType.name(), fieldName);
            }
        }
        return messageBuilder.build();
    }

    private long deserializeLong(byte[] bytes, BytesRange bytesRange, String fieldName) {
        int firstByte = bytesRange.getFromByte();
        int lastByte = bytesRange.getToByte();
        int bytesSize = lastByte - firstByte;
        checkArgument(bytesSize <= 8,
                "Only integer fields with declared bytes size in [1, 8] are supported. Field %s has %s bytes size.",
                fieldName,
                bytesSize);

        long result = 0;
        if (byteOrder.equals(ByteOrder.BIG_ENDIAN)) {
            for (int i = firstByte; i < lastByte; i++) {
                result <<= 8;
                result |= bytes[i] & 0xFF;
            }
        } else {
            for (int i = lastByte; i > firstByte; i--) {
                result <<= 8;
                result |= bytes[i - 1] & 0xFF;
            }
        }
        return result;
    }

    private EnumValueDescriptor deserializeEnum(byte[] bytes, FieldDescriptor fieldDescriptor, BytesRange bytesRange) {
        return fieldDescriptor.getEnumType()
                .findValueByNumber((int) deserializeLong(bytes, bytesRange, fieldDescriptor.getName()));
    }

    private boolean deserializeBoolean(byte[] bytes, BytesRange bytesRange, String fieldName) {
        int bytesSize = bytesRange.getToByte() - bytesRange.getFromByte();
        checkArgument(bytesSize == 1,
                "Boolean value %s has bytes size = %s that more than 1 byte",
                fieldName,
                bytesSize);
        return bytes[bytesRange.getFromByte()] != 0;
    }

    private static int getMessageBytesSize(Message message) {
        return message.getDescriptorForType().getOptions().getExtension(Blerpc.messageSize).getMessageSizeBytes();
    }

    private static BytesRange getBytesRange(FieldDescriptor descriptor) {
        return descriptor.getOptions().getExtension(Blerpc.filedBytes);
    }

    private static void validateMessageSchema(Message message, BytesRange messageBytesRange) {
        checkHasExpectedBytesSize(message, messageBytesRange);
        List<FieldDescriptor> fields = message.getDescriptorForType().getFields();
        for (FieldDescriptor field : fields) {
            checkFieldHasByteRangeOption(field);
            checkBytesRangeValid(getBytesRange(field), getMessageBytesSize(message), field);
        }
        checkBytesRangesNotIntersect(fields);
    }

    private static void checkHasSizeAnnotation(Message message) {
        Descriptor descriptor = message.getDescriptorForType();
        checkArgument(descriptor.getOptions().hasExtension(Blerpc.messageSize) || descriptor.getFields().isEmpty(),
                "A non empty message %s doesn't have com.blerpc.message_size annotation.",
                descriptor.getName());
    }

    private static void checkHasExpectedBytesSize(Message message, BytesRange bytesRange) {
        int messageBytesSize = getMessageBytesSize(message);
        int expectedBytesSize = bytesRange.getToByte() - bytesRange.getFromByte();
        checkArgument(messageBytesSize == expectedBytesSize,
                "Non-primitive message %s has declared size %s, which is not equal to the size of it's type %s.",
                message.getDescriptorForType().getName(),
                expectedBytesSize,
                messageBytesSize);
    }

    private static void checkFieldHasByteRangeOption(FieldDescriptor descriptor) {
        checkArgument(descriptor.getOptions().hasExtension(Blerpc.filedBytes),
                "Proto field %s doesn't have com.blerpc.field_bytes annotation",
                descriptor.getName());
    }

    private static void checkBytesRangeValid(BytesRange bytesRange, int messageBytesSize, FieldDescriptor descriptor) {
        String name = descriptor.getName();
        int firstByte = bytesRange.getFromByte();
        int lastByte = bytesRange.getToByte();
        checkArgument(firstByte < lastByte,
                "Field %s has from_bytes = %s which must be less than to_bytes = %s",
                name,
                firstByte,
                lastByte);
        checkArgument(firstByte >= 0,
                "Field %s has from_bytes = %s which is less than zero",
                name,
                firstByte);
        checkArgument(lastByte <= messageBytesSize,
                "Field %s has to_bytes = %s which is bigger than message bytes size = %s",
                name,
                lastByte,
                messageBytesSize);
    }

    private static void checkBytesRangesNotIntersect(List<FieldDescriptor> fields) {
        ImmutableList<BytesRange> ranges = FluentIterable.from(fields).transform(AnnotationMessageConverter::getBytesRange).toList();
        for (int i = 0; i < fields.size(); i++) {
            final BytesRange bytesRange = ranges.get(i);
            Optional<BytesRange> intersectedField = FluentIterable.from(ranges)
                    .skip(i + 1)
                    .firstMatch(rangeForMatch -> bytesRangesIntersect(bytesRange, rangeForMatch));
            checkArgument(!intersectedField.isPresent(),
                    "Field %s bytes range intersects with another field bytes range",
                    fields.get(i).getName());
        }
    }

    private static boolean bytesRangesIntersect(BytesRange firstRange, BytesRange secondRange) {
        boolean firstRangeBeforeSecondRange = firstRange.getFromByte() < secondRange.getFromByte();
        return firstRangeBeforeSecondRange
                ? firstRange.getToByte() > secondRange.getFromByte()
                : secondRange.getToByte() > firstRange.getFromByte();
    }

    private static void checkBytesRangeEnoughForEnum(EnumValueDescriptor enumValueDescriptor, BytesRange bytesRange, String fieldName) {
        int bytesSize = bytesRange.getToByte() - bytesRange.getFromByte();
        checkArgument(bytesSize <= 2,
                "Only enum fields with declared bytes size in [1, 2] are supported. Field %s has %s bytes size.",
                fieldName,
                bytesSize);
        EnumDescriptor enumDescriptor = enumValueDescriptor.getType();
        long valuesCount = enumDescriptor.getValues().size();
        checkArgument(Math.pow(2, 8 * bytesSize) - 1 >= valuesCount,
                "%s byte(s) not enough for %s enum that has %s values",
                bytesSize,
                enumDescriptor.getName(),
                valuesCount);
    }

    private BytesRange getRelativeBytesRange(BytesRange requestBytesRange, FieldDescriptor fieldDescriptor) {
        int firstByte = requestBytesRange.getFromByte();
        BytesRange fieldBytesRange = getBytesRange(fieldDescriptor);
        return BytesRange.newBuilder()
                .setFromByte(fieldBytesRange.getFromByte() + firstByte)
                .setToByte(fieldBytesRange.getToByte() + firstByte)
                .build();
    }
}
