import dev.xr.rayneo.probe.BusinessEnvelope;
import java.util.Arrays;

public class BusinessEnvelopeCheck {
    static void rejected(byte[] bytes) throws Exception {
        try { BusinessEnvelope.decode(bytes); }
        catch (Exception expected) { return; }
        throw new AssertionError("Malformed packet accepted");
    }
    public static void main(String[] args) throws Exception {
        byte[] known = {8,1,16,1,26,2,123,125};
        if (!Arrays.equals(known, BusinessEnvelope.encode(1, "{}"))) throw new AssertionError("Wire fixture");
        // Turbo-IO AssistantRecorderPrototype.control(start:true), explicit empty data field.
        byte[] iosRecorder = {8,1,16,2,26,8,123,34,114,99,34,58,49,125,34,0};
        if (!Arrays.equals(iosRecorder, BusinessEnvelope.encode(2, "{\"rc\":1}", true)))
            throw new AssertionError("iOS recorder byte parity");
        byte[] complete = {8,1,16,12,26,2,123,125,34,0};
        if (!Arrays.equals(complete, BusinessEnvelope.encode(12, "{}", true))) throw new AssertionError("iOS response-complete parity");
        String text = "{\"text\":\"" + "雷鸟".repeat(100) + "\"}";
        BusinessEnvelope decoded = BusinessEnvelope.decode(BusinessEnvelope.encode(32, text));
        if (decoded.type != 32 || !text.equals(decoded.json)) throw new AssertionError("UTF-8 length");
        BusinessEnvelope audio = BusinessEnvelope.decode(new byte[]{8,1,16,3,26,0,34,3,1,2,3});
        if (audio.type != 3 || audio.dataBytes != 3) throw new AssertionError("Audio metadata length");
        if (audio.audio != null) throw new AssertionError("Metadata-only test retained audio");
        byte[] original = {8,1,16,3,26,0,34,3,1,2,3};
        BusinessEnvelope captured = BusinessEnvelope.decode(original, true);
        original[8] = 99;
        if (!Arrays.equals(captured.audio, new byte[]{1,2,3})) throw new AssertionError("Owned audio copy");
        if (BusinessEnvelope.decode(new byte[]{8,1,16,1,34,1,9}, true).audio != null) throw new AssertionError("Non-audio retained");
        rejected(new byte[]{8,1,16,3,34,3,1,2});
        rejected(new byte[]{8,1,16,1,26,3,123,125}); // truncated body
        rejected(new byte[]{8,1,16,1,16,2}); // duplicate type
        rejected(new byte[]{8,1,18,0}); // wrong wire type
        rejected(new byte[]{8,2,16,1}); // incompatible version
        rejected(new byte[]{8,1,16,1,26,1,(byte)255}); // invalid UTF-8
        rejected(new byte[]{8,1,16,(byte)128}); // truncated varint
        rejected(new byte[]{8,1,16,1,0}); // tag zero
        rejected(new byte[]{8,1,16,1,26,(byte)255,(byte)255,(byte)255,(byte)255,127});
        rejected(new byte[]{8,1,16,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,2});
        System.out.println("Business envelope fixtures and malformed input checks passed");
    }
}
