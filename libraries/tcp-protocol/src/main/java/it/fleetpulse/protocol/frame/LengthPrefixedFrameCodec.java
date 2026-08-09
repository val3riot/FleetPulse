package it.fleetpulse.protocol.frame;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public final class LengthPrefixedFrameCodec {

    private LengthPrefixedFrameCodec() {
    }

    public static byte[] read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] header = new byte[ProtocolConstants.HEADER_SIZE_BYTES];
        int headerBytesRead = readFully(input, header);
        if (headerBytesRead == 0) {
            throw new FrameStreamClosedException();
        }
        if (headerBytesRead < header.length) {
            throw new TruncatedFrameHeaderException(headerBytesRead);
        }

        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header)
                .order(ByteOrder.BIG_ENDIAN).getInt());
        validateLength(length);

        byte[] payload = new byte[(int) length];
        int payloadBytesRead = readFully(input, payload);
        if (payloadBytesRead < payload.length) {
            throw new TruncatedFramePayloadException(payload.length, payloadBytesRead);
        }
        return payload;
    }

    public static void write(byte[] payload, OutputStream output) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(output, "output");
        validateLength(payload.length);

        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    private static void validateLength(long length) throws InvalidFrameLengthException {
        if (length == 0) {
            throw new InvalidFrameLengthException(length);
        }
        if (length > ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES) {
            throw new FrameTooLargeException(length);
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count == -1) {
                break;
            }
            if (count == 0) {
                int nextByte = input.read();
                if (nextByte == -1) {
                    break;
                }
                buffer[offset++] = (byte) nextByte;
            } else {
                offset += count;
            }
        }
        return offset;
    }
}
