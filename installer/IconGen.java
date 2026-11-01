import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Writes a multi-size PNG-compressed ICO of the tray icon design (blue disc, white centre dot).
 * Usage: java installer/IconGen.java out.ico        (generate)
 *        java installer/IconGen.java --verify in.ico (check header and entry count)
 */
public class IconGen {

	static final int[] SIZES = { 16, 32, 48, 256 };

	public static void main(String[] args) throws IOException {
		if (args.length == 2 && args[0].equals("--verify")) {
			verify(Path.of(args[1]));
			return;
		}
		if (args.length != 1) {
			System.err.println("usage: IconGen <out.ico> | IconGen --verify <in.ico>");
			System.exit(2);
		}
		List<byte[]> pngs = new ArrayList<>();
		for (int s : SIZES) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(draw(s), "png", out);
			pngs.add(out.toByteArray());
		}
		int offset = 6 + 16 * SIZES.length;
		ByteBuffer buf = ByteBuffer.allocate(offset + pngs.stream().mapToInt(p -> p.length).sum())
			.order(ByteOrder.LITTLE_ENDIAN);
		buf.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
		for (int i = 0; i < SIZES.length; i++) {
			int s = SIZES[i];
			buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
			buf.putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
			offset += pngs.get(i).length;
		}
		for (byte[] p : pngs) {
			buf.put(p);
		}
		Files.write(Path.of(args[0]), buf.array());
		System.out.println("Wrote " + args[0] + " (" + buf.array().length + " bytes)");
	}

	/** Same design as TrayIconManager.drawIcon (16px: disc 0..16, dot at 5..11), scaled. */
	static BufferedImage draw(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0x2563EB));
			g.fillOval(0, 0, size, size);
			g.setColor(Color.WHITE);
			int d = Math.round(size * 6f / 16f);
			int o = Math.round(size * 5f / 16f);
			g.fillOval(o, o, d, d);
		}
		finally {
			g.dispose();
		}
		return img;
	}

	static void verify(Path file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		int reserved = b.getShort(0), type = b.getShort(2), count = b.getShort(4);
		if (reserved != 0 || type != 1 || count != SIZES.length) {
			System.err.println("INVALID: reserved=" + reserved + " type=" + type + " count=" + count);
			System.exit(1);
		}
		for (int i = 0; i < count; i++) {
			int base = 6 + 16 * i;
			int len = b.getInt(base + 8), off = b.getInt(base + 12);
			if (b.get(off) != (byte) 0x89 || b.get(off + 1) != 'P' || off + len > b.limit()) {
				System.err.println("INVALID: entry " + i + " is not a PNG payload");
				System.exit(1);
			}
		}
		System.out.println("OK: header 00 00 01 00, " + count + " PNG entries");
	}
}
