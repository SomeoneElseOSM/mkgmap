/*
 * Copyright (C) 2009.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */
package uk.me.parabola.imgfmt.app.trergn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.BitReader;
import uk.me.parabola.imgfmt.app.BufferedImgFileReader;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.ImgFileReader;
import uk.me.parabola.imgfmt.app.ImgReader;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.lbl.LBLFileReader;
import uk.me.parabola.imgfmt.app.lbl.POIRecord;
import uk.me.parabola.imgfmt.app.net.NETFileReader;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * The region file.  Holds actual details of points and lines etc.
 *
 * This is the view of the file when it is being read.  Use {@link RGNFile}
 * for writing the file.
 *
 * The main focus of mkgmap is creating files, there are plenty of applications
 * that read and display the data, reading is implemented only to the
 * extent required to support creating the various auxiliary files etc.
 * 
 * @author Steve Ratcliffe
 */
public class RGNFileReader extends ImgReader {
	private static final Logger log = Logger.getLogger(RGNFileReader.class);

	private final RGNHeader rgnHeader;
	private LBLFileReader lblFile;
	private NETFileReader netFile;

	public RGNFileReader(ImgChannel chan) {
		rgnHeader = new RGNHeader();
		setHeader(rgnHeader);

		setReader(new BufferedImgFileReader(chan));
		rgnHeader.readHeader(getReader());
	}

	public void config(EnhancedProperties props) {
		//config = props;
	}

	/**
	 * Get a list of all points for the given subdivision.  This includes
	 * both the indexed points section and the points section.
	 *
	 * The numbering of the points carries through the sectins.
	 * @param sd The subdivision that we are interested in.
	 * @return A list of all points for the subdiv.
	 */
	public List<Point> pointsForSubdiv(Subdivision sd) {
		if (!sd.hasIndPoints() && !sd.hasPoints())
			return Collections.emptyList();

		RgnOffsets rgnOffsets = getOffsets(sd);
		ArrayList<Point> list = new ArrayList<Point>();

		// Even though the indexed points are after the points, the numbering
		// starts with 1 for the first indexed point and carries on into the
		// points section.
		fetchPointsCommon(sd, rgnOffsets.getIndPointStart(), rgnOffsets.getIndPointEnd(), list);
		fetchPointsCommon(sd, rgnOffsets.getPointStart(), rgnOffsets.getPointEnd(), list);
		return list;
	}

	/**
	 * The indexed points and the points sections are both read just the same.
	 */
	private void fetchPointsCommon(Subdivision sd, long start, long end, List<Point> points) {
		position(start);
		ImgFileReader reader = getReader();

		int number = points.size() + 1;
		while (position() < end) {
			Point p = new Point(sd);

			byte t = reader.get();
			int val = reader.getu3();
			boolean hasSubtype = false;
			if ((val & 0x800000) != 0)
				hasSubtype = true;

			boolean hasPoi = false;
			if ((val & 0x400000) != 0)
				hasPoi = true;

			Label l;
			int labelOffset = val & 0x3fffff;
			if (hasPoi) {
				POIRecord record = lblFile.fetchPoi(labelOffset);
				if (record != null) {
					l = record.getNameLabel();
					p.setPOIRecord(record);
				} else
					l = lblFile.fetchLabel(0);
			} else {
				l = lblFile.fetchLabel(labelOffset);
			}
			p.setLabel(l);

			p.setDeltaLong((short)reader.getChar());
			p.setDeltaLat((short)reader.getChar());

			if (hasSubtype) {
				byte st = reader.get();
				p.setType(((t & 0xff) << 8) | (st & 0xff));
				//p.setHasSubtype(true);
			} else {
				p.setType(t & 0xff);
			}

			p.setNumber(number++);
			points.add(p);
		}
	}

	/**
	 * Get all the lines for a given subdivision.
	 * @param div The subdivision we want the line from.
	 * @return A list of lines.
	 */
	public List<Polyline> linesForSubdiv(Subdivision div) {
		if (!div.hasPolylines())
			return Collections.emptyList();

		RgnOffsets rgnOffsets = getOffsets(div);
		ArrayList<Polyline> list = new ArrayList<Polyline>();

		int start = rgnOffsets.getLineStart();
		int end = rgnOffsets.getLineEnd();

		position(start);
		ImgFileReader reader = getReader();
		while (position() < end) {
			Polyline line = new Polyline(div);
			readLineCommon(reader, div, line);
			list.add(line);
		}

		return list;
	}

	/**
	 * Get all the polygons for a given subdivision.
	 */
	public List<Polygon> shapesForSubdiv(Subdivision div) {
		if (!div.hasPolygons())
			return Collections.emptyList();

		RgnOffsets rgnOffsets = getOffsets(div);
		ArrayList<Polygon> list = new ArrayList<Polygon>();

		int start = rgnOffsets.getPolygonStart();
		int end = rgnOffsets.getPolygonEnd();

		position(start);
		ImgFileReader reader = getReader();

		while (position() < end) {
			Polygon line = new Polygon(div);
			readLineCommon(reader, div, line);
			list.add(line);
		}

		return list;
	}

	/**
	 * Since polygons are pretty much like polylines in the img format the
	 * reading code can be shared.
	 *
	 * @param reader The reader for the img file.
	 * @param div The subdivision.
	 * @param line The line or shape that is to be populated.
	 */
	private void readLineCommon(ImgFileReader reader, Subdivision div, Polyline line) {
		byte type = reader.get();
		line.setType(type & 0x3f);

		int labelOffset = reader.getu3();
		Label label;
		if ((labelOffset & 0x800000) == 0) {
			label = lblFile.fetchLabel(labelOffset & 0x3fffff);
		} else {
			int labelOffsetFromNet = netFile.getLabelOffset(labelOffset & 0x3fffff);
			label = lblFile.fetchLabel(labelOffsetFromNet);
		}
		line.setLabel(label);

		// Extra bit (for bit stream)
		boolean extra = (labelOffset & 0x400000) != 0;

		line.setDeltaLong((short)reader.getChar());
		line.setDeltaLat((short)reader.getChar());

		int len;
		if ((type & 0x80) == 0)
			len = reader.get() & 0xff;
		else
			len = reader.getChar();

		int base = reader.get();

		byte[] bitstream = reader.get(len);
		BitReader br = new BitReader(bitstream);

		// This reads the bit stream and adds all the points found
		readBitStream(br, div, line, extra, len, base);
	}


	/**
	 * Read the bit stream for a single line in the file.
	 * @param br The bit stream reader.
	 * @param div The subdivision that the line is in.
	 * @param line The line itself.
	 * @param extra True if there is an 'extra' bit in the stream. Used for nodes.
	 * @param len The length of the stream.
	 * @param base The base size of the deltas.
	 */
	private void readBitStream(BitReader br, Subdivision div, Polyline line, boolean extra, int len, int base) {
		int currLat = line.getLat();
		int currLon = line.getLong();

		log.debug(String.format("Start point %.5f,%.5f",
				Utils.toDegrees(currLat),
				Utils.toDegrees(currLon)));

		int xbase = 2;
		int n = base & 0xf;
		if (n <= 9)
			xbase += n;
		else
			xbase += (2 * n) - 9;

		n = (base >>> 4) & 0xf;
		int ybase = 2;
		if (n <= 9)
			ybase += n;
		else
			ybase += (2 * n) - 9;

		if (len == 0)
			return;

		boolean xneg = false;
		boolean xsame = br.get1();
		if (xsame) {
			xneg = br.get1();
		} else
			xbase++;

		boolean ysame = br.get1();
		boolean yneg = false;
		if (ysame) {
			yneg = br.get1();
		} else
			ybase++;

		if (extra) {
			boolean firstextra = br.get1();
			log.debug("the first extra bit is", firstextra);
		}

		// All is now prepared, read the actual deltas and decode them into
		// proper lat/long coords.
		while (br.getBitPosition() <= 8* len - ((extra ? 1:0) + xbase + ybase)) {
			br.getBitPosition();

			int dx;
			if (xsame) {
				dx = br.get(xbase);
				if (xneg)
					dx = -dx;
			} else {
				dx = br.sget2(xbase);
			}

			int dy;
			if (ysame) {
				dy = br.get(ybase);
				if (yneg)
					dy = -dy;
			} else {
				dy = br.sget2(ybase);
			}

			boolean isnode = false;
			if (extra)
				isnode = br.get1();

			currLat += dy << (24 - div.getResolution());
			currLon += dx << (24 - div.getResolution());
			Coord coord;
			if (isnode)
				coord = new CoordNode(currLat, currLon, 0/* XXX */, false);
			else
				coord = new Coord(currLat, currLon);

			line.addCoord(coord);
		}
	}

	/**
	 * Get the offsets to the points, lines etc in RGN for the given subdiv.
	 * @param sd The subdivision is needed to work out the starting points.
	 * @return An Offsets class that allows you to obtain the offsets.
	 */
	private RgnOffsets getOffsets(Subdivision sd) {
		int off = sd.getStartRgnPointer();
		position(rgnHeader.getDataOffset() + off);

		return new RgnOffsets(sd);
	}

	public void setLblFile(LBLFileReader lblFile) {
		this.lblFile = lblFile;
	}

	public void setNetFile(NETFileReader netFile) {
		this.netFile = netFile;
	}

	/**
	 * Class to hold the start and end points of point, lines etc within
	 * the area for a given subdivision in the RGN data.
	 */
	private class RgnOffsets {
		private final int pointOffset;
		private int pointEnd;

		private int indPointOffset;
		private int indPointEnd;

		private int lineOffset;
		private int lineEnd;

		private int polygonOffset;
		private int polygonEnd;

		private final int start;
		private int headerLen;

		/**
		 * Calculate the offsets for the given subdivision.
		 * After this is called the position will be set after any pointers that
		 * exist at the beginning of the area.
		 *
		 * @param sd The subdivision.
		 */
		private RgnOffsets(Subdivision sd) {
			ImgFileReader reader = getReader();

			start = (int) position();
			
			pointOffset = 0;

			if (sd.needsIndPointPtr()) {
				indPointOffset = reader.getChar();
				headerLen += 2;
			}
			
			if (sd.needsPolylinePtr()) {
				lineOffset = reader.getChar();
				headerLen += 2;
			}

			if (sd.needsPolygonPtr()) {
				polygonOffset = reader.getChar();
				headerLen += 2;
			}


			if (sd.hasPoints()) {
				if (sd.hasIndPoints())
					pointEnd = indPointOffset;
				else if (sd.hasPolylines())
					pointEnd = lineOffset;
				else if (sd.hasPolygons())
					pointEnd = polygonOffset;
				else
					pointEnd = sd.getEndRgnPointer() - sd.getStartRgnPointer();
			}
			if (sd.hasIndPoints()) {
				if (sd.hasPolylines())
					indPointEnd = lineOffset;
				else if (sd.hasPolygons())
					indPointEnd = polygonOffset;
				else
					indPointEnd = sd.getEndRgnPointer() - sd.getStartRgnPointer();
			}
			if (sd.hasPolylines()) {
				if (sd.hasPolygons())
					lineEnd = polygonOffset;
				else
					lineEnd = sd.getEndRgnPointer() - sd.getStartRgnPointer();
			}
			if (sd.hasPolygons()) {
				polygonEnd = sd.getEndRgnPointer() - sd.getStartRgnPointer();
			}
		}

		public String toString() {
			return String.format("rgn div offsets: %x-%x/%x-%x/%x-%x/%x-%x",
					pointOffset, pointEnd, indPointOffset, indPointEnd,
					lineOffset, lineEnd, polygonOffset, polygonEnd);
		}

		public long getPointStart() {
			return pointOffset == 0 ? start + headerLen : start + pointOffset;
		}

		public long getPointEnd() {
			return start + pointEnd;
		}

		public long getIndPointStart() {
			return indPointOffset == 0 ? start + headerLen : start + indPointOffset;
		}

		public long getIndPointEnd() {
			return start + indPointEnd;
		}

		public int getLineStart() {
			return lineOffset == 0? start + headerLen: start + lineOffset;
		}

		public int getLineEnd() {
			return start + lineEnd;
		}

		public int getPolygonStart() {
			return polygonOffset == 0? start + headerLen: start + polygonOffset;
		}

		public int getPolygonEnd() {
			return start + polygonEnd;
		}
	}
}
