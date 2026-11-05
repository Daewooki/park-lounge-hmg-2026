import ai.nc.varcospaces.SafetyPolicy;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.file.*;
import java.util.*;

/** Host-side safety boundary and QR payload tests; not Android lifecycle tests. */
public final class CorePolicyTest {
    private static int assertions;
    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        for (int bits = 0; bits < 16; bits++) {
            boolean foreground = (bits & 1) != 0, connected = (bits & 2) != 0;
            boolean optimization = (bits & 4) != 0, restricted = (bits & 8) != 0;
            check(SafetyPolicy.mayInteract(foreground, connected, optimization, restricted ? 1 : 0) == (bits == 3),
                "Only known unrestricted foreground vehicle state permits interaction: " + bits);
        }
        check(!SafetyPolicy.mayInteract(true, true, false, Integer.MIN_VALUE), "Unknown restriction bits fail closed");
        for (int bits = 0; bits < 64; bits++) {
            boolean foreground = (bits & 1) != 0, connected = (bits & 2) != 0;
            boolean optimization = (bits & 4) != 0, restricted = (bits & 8) != 0;
            boolean gearAvailable = (bits & 16) != 0, parkSelected = (bits & 32) != 0;
            check(SafetyPolicy.mayUseParkedContent(foreground, connected, optimization, restricted ? 1 : 0,
                    gearAvailable, parkSelected) == (bits == 51),
                "CarUx AND available P-selection must both permit content: " + bits);
        }
        check(!SafetyPolicy.mayUseParkedContent(true, true, false, 0, true, false),
            "An unrestricted D/N/R at zero speed is still not Park");
        check(!SafetyPolicy.mayUseParkedContent(true, true, false, 0, false, true),
            "Unavailable gear cannot reuse a previously selected Park value");
        check(!SafetyPolicy.mayUseParkedContent(true, false, false, 0, true, true),
            "Car disconnect cannot reuse old Park and unrestricted UX values");
        for (String path : new String[]{null,"","../catalog.json","web/../catalog.json","web/%2e%2e/private","web/\\private","content/https:remote","web/\0secret","private/key","https://example.com/web/app.js"})
            check(!SafetyPolicy.isPackagedPath(path), "Reject outside or encoded asset path");
        for (String path : new String[]{"catalog.json","web/index.html","web/vendor/three.core.js","content/rain/model.glb"})
            check(SafetyPolicy.isPackagedPath(path), "Accept intended packaged content");
        check(SafetyPolicy.volume(Double.NaN) == 0, "NaN must not reach media volume");
        check(SafetyPolicy.volume(Double.POSITIVE_INFINITY) == 0, "Infinity must not reach media volume");
        check(SafetyPolicy.volume(-10) == 0 && SafetyPolicy.volume(10) == 1, "Clamp out of range volume");
        check(SafetyPolicy.volume(.5) == .5f, "Preserve valid volume");
        List<String> payloads = Files.readAllLines(Paths.get(args[0]));
        payloads.add("mailto:support@example.com?subject=%5BPark%20Lounge%5D%20rain&body=Version%3A%200.3.1");
        for (String payload : payloads) {
            Map<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 4); hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 640, hints);
            int[] pixels = new int[640 * 640];
            for (int y=0; y<640; y++) for (int x=0; x<640; x++) pixels[y*640+x] = matrix.get(x,y) ? 0xff000000 : 0xffffffff;
            Result decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(640,640,pixels))));
            check(payload.equals(decoded.getText()), "Product/report QR payload must survive encoding exactly");
        }
        System.out.println("Safety boundary and QR tests passed: " + assertions + " assertions.");
    }
}
