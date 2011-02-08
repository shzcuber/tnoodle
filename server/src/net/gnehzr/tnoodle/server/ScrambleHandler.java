package net.gnehzr.tnoodle.scrambles.server;

import static net.gnehzr.tnoodle.utils.Utils.GSON;
import static net.gnehzr.tnoodle.utils.Utils.toInt;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.Package;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.DefaultFontMapper;
import com.itextpdf.text.pdf.DefaultSplitCharacter;
import com.itextpdf.text.pdf.PdfChunk;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;

import com.sun.net.httpserver.HttpExchange;

import net.gnehzr.tnoodle.scrambles.Scrambler;

public class ScrambleHandler extends SafeHttpHandler {
	private static final int MAX_COUNT = 100;

	private SortedMap<String, Scrambler> scramblers;
	private String puzzleNamesJSON;
	public ScrambleHandler(SortedMap<String, Scrambler> scramblers) {
		this.scramblers = scramblers;
		
		//listing available scrambles
		String[][] puzzleNames = new String[scramblers.size()][2];
		int i = 0;
		for(Entry<String, Scrambler> scrambler : scramblers.entrySet()) {
			String shortName = scrambler.getValue().getShortName();
			String longName = scrambler.getValue().getLongName();
			puzzleNames[i][0] = shortName;
			puzzleNames[i][1] = longName;
			i++;
		}
		puzzleNamesJSON = GSON.toJson(puzzleNames);
	}
	
	private final DefaultSplitCharacter SPLIT_ON_SPACES = new DefaultSplitCharacter() {
		@Override
		public boolean isSplitCharacter(int start,
				int current, int end, char[] cc,
				PdfChunk[] ck) {
			return getCurrentCharacter(current, cc, ck) == ' '; //only allow splitting on spaces
		}
	};

	private ByteArrayOutputStream createPdf(Scrambler scrambler, String[] scrambles, String title, Integer width, Integer height, String scheme) {
		if(width == null)
			width = 200;
		if(height == null)
			height = (int) (PageSize.LETTER.getHeight()/5); //optimizing for 5 scrambles per page
			
		PdfWriter docWriter = null;
		try {
			Document doc = new Document(PageSize.LETTER, 0, 0, 75, 75);
			ByteArrayOutputStream baosPDF = new ByteArrayOutputStream();
			docWriter = PdfWriter.getInstance(doc, baosPDF);
			
			doc.addAuthor(this.getClass().getName());
			doc.addCreationDate();
			doc.addProducer();
			doc.addCreator(this.getClass().getName());
			if(title != null)
				doc.addTitle(title);
			
			docWriter.setBoxSize("art", new Rectangle(36, 54, PageSize.LETTER.getWidth()-36, PageSize.LETTER.getHeight()-54));
			docWriter.setPageEvent(new HeaderFooter(scrambler.getLongName(), title));

			doc.setPageSize(PageSize.LETTER);

			doc.open();

			Dimension dim = new Dimension(0, 0);
			HashMap<String, Color> colorScheme = null;
			dim = scrambler.getPreferredSize(width, height);
			colorScheme = scrambler.parseColorScheme(scheme);
			
			PdfPTable table = new PdfPTable(3);

			float maxWidth = 0;
			for(int i = 0; i < scrambles.length; i++) {
				String scramble = scrambles[i];
				Chunk ch = new Chunk((i+1)+".");
				maxWidth = Math.max(maxWidth, ch.getWidthPoint());
				PdfPCell nthscramble = new PdfPCell(new Paragraph(ch));
				nthscramble.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
				table.addCell(nthscramble);
				
				Chunk scrambleChunk = new Chunk(scramble);
				scrambleChunk.setSplitCharacter(SPLIT_ON_SPACES);
				try {
					BaseFont courier = BaseFont.createFont(BaseFont.COURIER, BaseFont.CP1252, BaseFont.EMBEDDED);
					scrambleChunk.setFont(new Font(courier, 12, Font.NORMAL));
				} catch(IOException e1) {
					e1.printStackTrace();
				}
				PdfPCell scrambleCell = new PdfPCell(new Paragraph(scrambleChunk));
				scrambleCell.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
				table.addCell(scrambleCell);
				
				try {
					PdfContentByte cb = docWriter.getDirectContent();
					PdfTemplate tp = cb.createTemplate(dim.width, dim.height);
					Graphics2D g2 = tp.createGraphics(dim.width, dim.height, new DefaultFontMapper());

					scrambler.drawScramble(g2, dim, scramble, colorScheme);
					g2.dispose();
					PdfPCell imgCell = new PdfPCell(Image.getInstance(tp), true);
					imgCell.setBackgroundColor(BaseColor.GRAY);
					imgCell.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
					table.addCell(imgCell);
				} catch (Exception e) {
					table.addCell("Error drawing scramble: " + e.getMessage());
					e.printStackTrace();
				}
			}
			maxWidth*=2; //TODO - i have no freaking clue why i need to do this
			table.setTotalWidth(new float[] { maxWidth, doc.getPageSize().getWidth()-maxWidth-dim.width, dim.width });
			doc.add(table);

			
			doc.close();
			return baosPDF;
		} catch (DocumentException e) {
			e.printStackTrace();
		} finally {
			docWriter.close();
		}
		return null;
	}
	
	class HeaderFooter extends PdfPageEventHelper {
		private String header;
		public HeaderFooter(String puzzle, String title) {
			header = puzzle + (title == null ? "" : " " + title);
		}
		public void onEndPage(PdfWriter writer, Document document) {
			Rectangle rect = writer.getBoxSize("art");
			//TODO - urgh... http://stackoverflow.com/questions/759909/how-to-add-total-page-number-on-every-page-with-itext	            
			ColumnText.showTextAligned(writer.getDirectContent(),
					Element.ALIGN_CENTER, new Phrase(header + " page " + writer.getPageNumber()),
					(rect.getLeft() + rect.getRight()) / 2, rect.getTop(), 0);
		}
	}
	
	protected void wrappedHandle(HttpExchange t, String[] path, HashMap<String, String> query) {
		if(path.length == 1) {
			sendJSON(t, puzzleNamesJSON, query.get("callback"));
		} else {
			String puzzle, title, ext;
			String[] puzzle_title_ext = path[1].split("\\.");
			switch(puzzle_title_ext.length) {
			case 1:
				puzzle = puzzle_title_ext[0];
				title = null;
				ext = null;
				break;
			case 2:
				puzzle = puzzle_title_ext[0];
				title = null;
				ext = puzzle_title_ext[1];
				break;
			case 3:
				puzzle = puzzle_title_ext[0];
				title = puzzle_title_ext[1];
				ext = puzzle_title_ext[2];
				break;
			default:
				sendText(t, "Invalid number of periods: " + path[1]);
				return;
			}
			Scrambler scrambler = scramblers.get(puzzle);
			if(scrambler == null) {
				sendText(t, "Invalid scrambler: " + puzzle);
				return;
			}

			String seed = query.get("seed");
			int count = Math.min(toInt(query.get("count"), 1), MAX_COUNT);
			String[] scrambles;
			if(seed != null) {
				int offset = Math.min(toInt(query.get("offset"), 0), MAX_COUNT);
				scrambles = scrambler.generateSeededScrambles(seed, count, offset);
			} else
				scrambles = scrambler.generateScrambles(count);

			if(ext == null || ext.equals("txt")) {
				StringBuilder sb = new StringBuilder();
				for(int i = 0; i < scrambles.length; i++) {
					String scramble = scrambles[i];
					// We replace newlines with spaces
					sb.append(i + ". " + scramble.replaceAll("\n", " ")).append("\r\n");
				}
				sendText(t, sb.toString());
			} else if(ext.equals("json")) {
				sendJSON(t, GSON.toJson(scrambles), query.get("callback"));
			} else if(ext.equals("pdf")) {
				ByteArrayOutputStream pdf = createPdf(scrambler, scrambles, title, toInt(query.get("width"), null), toInt(query.get("height"), null), query.get("scheme"));
				t.getResponseHeaders().set("Content-Disposition", "inline");
				//TODO - what's the right way to do caching?
				sendBytes(t, pdf, "application/pdf");
			} else {
				sendText(t, "Invalid extension: " + ext);
			}
		}
	}
}
