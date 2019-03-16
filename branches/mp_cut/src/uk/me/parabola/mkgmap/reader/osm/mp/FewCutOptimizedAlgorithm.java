package uk.me.parabola.mkgmap.reader.osm.mp;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation.JoinedWay;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.Java2DConverter;

public class FewCutOptimizedAlgorithm implements MultipolygonCutAlgorithm {
	private static final Logger log = Logger
			.getLogger(FewCutOptimizedAlgorithm.class);

	private MultiPolygonRelation mpRel;
	private Area bboxArea;
	
	private static class AreaCutData {
		Area outerArea;
		List<Area> innerAreas;
	}

	private static class CutPoint implements Comparable<CutPoint>{
		int startPoint = Integer.MAX_VALUE;
		int stopPoint = Integer.MIN_VALUE;
		final LinkedList<Area> areas;
		private final Comparator<Area> comparator;
		private final CoordinateAxis axis;

		public CutPoint(CoordinateAxis axis) {
			this.axis = axis;
			this.areas = new LinkedList<Area>();
			this.comparator = (axis == CoordinateAxis.LONGITUDE ? COMP_LONG_STOP : COMP_LAT_STOP);
		}
		
		public CutPoint duplicate() {
			CutPoint newCutPoint = new CutPoint(this.axis);
			newCutPoint.areas.addAll(areas);
			newCutPoint.startPoint = startPoint;
			newCutPoint.stopPoint = stopPoint;
			return newCutPoint;
		}

		private boolean isGoodCutPoint() {
			// It is better if the cutting line is on a multiple of 2048. 
			// Otherwise MapSource and QLandkarteGT paints gaps between the cuts
			return getCutPoint() % 2048 == 0;
		}
		
		public int getCutPoint() {
			int cutPoint = startPoint + (stopPoint - startPoint) / 2;
			
			// try to find a cut point that is a multiple of 2048 to 
			// avoid that gaps are painted by MapSource and QLandkarteGT
			// between the cutting lines
			int cutMod = cutPoint % 2048;
			if (cutMod == 0) {
				return cutPoint;
			}
			
			int cut1 = (cutMod > 0 ? cutPoint-cutMod : cutPoint  - 2048- cutMod);
			if (cut1 >= startPoint && cut1 <= stopPoint) {
				return cut1;
			}
			
			int cut2 = (cutMod > 0 ? cutPoint + 2048 -cutMod : cutPoint - cutMod);
			if (cut2 >= startPoint && cut2 <= stopPoint) {
				return cut2;
			}
			
			return cutPoint;
		}

		public Rectangle getCutRectangleForArea(Area toCut, boolean firstRect) {
			Rectangle areaRect = toCut.getBounds();
			if (axis == CoordinateAxis.LONGITUDE) {
				int newWidth = getCutPoint()-areaRect.x;
				if (firstRect) {
					return new Rectangle(areaRect.x, areaRect.y, newWidth, areaRect.height); 
				} else {
					return new Rectangle(areaRect.x+newWidth, areaRect.y, areaRect.width-newWidth, areaRect.height); 
				}
			} else {
				int newHeight = getCutPoint()-areaRect.y;
				if (firstRect) {
					return new Rectangle(areaRect.x, areaRect.y, areaRect.width, newHeight); 
				} else {
					return new Rectangle(areaRect.x, areaRect.y+newHeight, areaRect.width, areaRect.height-newHeight); 
				}
			}
		}
		
		public Collection<Area> getAreas() {
			return areas;
		}

		public void addArea(Area area) {
			// remove all areas that do not overlap with the new area
			while (!areas.isEmpty() && axis.getStop(areas.getFirst()) < axis.getStart(area)) {
				// remove the first area
				areas.removeFirst();
			}

			areas.add(area);
			Collections.sort(areas, comparator);
			startPoint = axis.getStart(Collections.max(areas,
				(axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START
						: COMP_LAT_START)));
			stopPoint = axis.getStop(areas.getFirst());
		}

		public int getNumberOfAreas() {
			return this.areas.size();
		}

		public int compareTo(CutPoint o) {
			if (this == o) {
				return 0;
			}
			
			if (isGoodCutPoint() != o.isGoodCutPoint()) {
				if (isGoodCutPoint())
					return 1;
				else
					return -1;
			}
			
			int ndiff = getNumberOfAreas()-o.getNumberOfAreas();
			if (ndiff != 0) {
				return ndiff;
			}
			// prefer the larger area that is split
			return (stopPoint-startPoint)-(o.stopPoint-o.startPoint); 
		}

		public String toString() {
			return axis +" "+getNumberOfAreas()+" "+startPoint+" "+stopPoint+" "+getCutPoint();
		}
		
	}

	private static enum CoordinateAxis {
		LATITUDE(false), LONGITUDE(true);

		private CoordinateAxis(boolean useX) {
			this.useX = useX;
		}

		private final boolean useX;

		public int getStart(Area area) {
			return getStart(area.getBounds());
		}

		public int getStart(Rectangle rect) {
			return (useX ? rect.x : rect.y);
		}

		public int getStop(Area area) {
			return getStop(area.getBounds());
		}

		public int getStop(Rectangle rect) {
			return (useX ? rect.x + rect.width : rect.y + rect.height);
		}

	}
	private static final AreaComparator COMP_LONG_START = new AreaComparator(
			true, CoordinateAxis.LONGITUDE);
	private static final AreaComparator COMP_LONG_STOP = new AreaComparator(
			false, CoordinateAxis.LONGITUDE);
	private static final AreaComparator COMP_LAT_START = new AreaComparator(
			true, CoordinateAxis.LATITUDE);
	private static final AreaComparator COMP_LAT_STOP = new AreaComparator(
			false, CoordinateAxis.LATITUDE);

	private static class AreaComparator implements Comparator<Area> {

		private final CoordinateAxis axis;
		private final boolean startPoint;

		public AreaComparator(boolean startPoint, CoordinateAxis axis) {
			this.startPoint = startPoint;
			this.axis = axis;
		}

		public int compare(Area o1, Area o2) {
			if (o1 == o2) {
				return 0;
			}

			if (startPoint) {
				int cmp = axis.getStart(o1) - axis.getStart(o2);
				if (cmp == 0) {
					return axis.getStop(o1) - axis.getStop(o2);
				} else {
					return cmp;
				}
			} else {
				int cmp = axis.getStop(o1) - axis.getStop(o2);
				if (cmp == 0) {
					return axis.getStart(o1) - axis.getStart(o2);
				} else {
					return cmp;
				}
			}
		}

	}
	
	
	public FewCutOptimizedAlgorithm() {
		// TODO Auto-generated constructor stub
	}

	public void init(MultiPolygonRelation mpRel) {
		this.mpRel = mpRel;
		bboxArea = Java2DConverter.createBoundsArea(mpRel.getBbox());
	}

	
	/**
	 * Cut out all inner polygons from the outer polygon. This will divide the outer
	 * polygon in several polygons.
	 * 
	 * @param outerPolygon
	 *            the outer polygon
	 * @param innerPolygons
	 *            a list of inner polygons
	 * @return a list of polygons that make the outer polygon cut by the inner
	 *         polygons
	 */
	public List<Way> cutOutInnerPolygons(Way outerPolygon, List<Way> innerPolygons) {
		if (innerPolygons.isEmpty()) {
			Way outerWay = new JoinedWay(outerPolygon);
			if (log.isDebugEnabled()) {
				log.debug("Way", outerPolygon.getId(), "splitted to way", outerWay.getId());
			}
			return Collections.singletonList(outerWay);
		}

		// use the java.awt.geom.Area class because it's a quick
		// implementation of what's needed

		// this list contains all non overlapping and singular areas
		// of the outerPolygon
		Queue<AreaCutData> areasToCut = new LinkedList<AreaCutData>();
		Collection<Area> finishedAreas = new ArrayList<Area>(innerPolygons.size());
		
		// create a list of Area objects from the outerPolygon (clipped to the bounding box)
		List<Area> outerAreas = createAreas(outerPolygon, true);
		
		// create the inner areas
		List<Area> innerAreas = new ArrayList<Area>(innerPolygons.size()+2);
		for (Way innerPolygon : innerPolygons) {
			// don't need to clip to the bounding box because 
			// these polygons are just used to cut out holes
			innerAreas.addAll(createAreas(innerPolygon, false));
		}

		// initialize the cut data queue
		if (innerAreas.isEmpty()) {
			// this is a multipolygon without any inner areas
			// nothing to cut
			finishedAreas.addAll(outerAreas);
		} else if (outerAreas.size() == 1) {
			// there is one outer area only
			// it is checked before that all inner areas are inside this outer area
			AreaCutData initialCutData = new AreaCutData();
			initialCutData.outerArea = outerAreas.get(0);
			initialCutData.innerAreas = innerAreas;
			areasToCut.add(initialCutData);
		} else {
			// multiple outer areas
			for (Area outerArea : outerAreas) {
				AreaCutData initialCutData = new AreaCutData();
				initialCutData.outerArea = outerArea;
				initialCutData.innerAreas = new ArrayList<Area>(innerAreas
						.size());
				for (Area innerArea : innerAreas) {
					if (outerArea.getBounds().intersects(
						innerArea.getBounds())) {
						initialCutData.innerAreas.add(innerArea);
					}
				}
				
				if (initialCutData.innerAreas.isEmpty()) {
					// this is either an error
					// or the outer area has been cut into pieces on the tile bounds
					finishedAreas.add(outerArea);
				} else {
					areasToCut.add(initialCutData);
				}
			}
		}

		while (!areasToCut.isEmpty()) {
			AreaCutData areaCutData = areasToCut.poll();
			CutPoint cutPoint = calcNextCutPoint(areaCutData);
			
			if (cutPoint == null) {
				finishedAreas.add(areaCutData.outerArea);
				continue;
			}
			
			assert cutPoint.getNumberOfAreas() > 0 : "Number of cut areas == 0 in "+mpRel.getId();
			
			// cut out the holes
			for (Area cutArea : cutPoint.getAreas()) {
				areaCutData.outerArea.subtract(cutArea);
			}
			
			if (areaCutData.outerArea.isEmpty()) {
				// this outer area space can be abandoned
				continue;
			} 
			
			// the inner areas of the cut point have been processed
			// they are no longer needed
			for (Area cutArea : cutPoint.getAreas()) {
				ListIterator<Area> areaIter = areaCutData.innerAreas.listIterator();
				while (areaIter.hasNext()) {
					Area a = areaIter.next();
					if (a == cutArea) {
						areaIter.remove();
						break;
					}
				}
			}
			// remove all does not seem to work. It removes more than the identical areas.
//			areaCutData.innerAreas.removeAll(cutPoint.getAreas());

			if (areaCutData.outerArea.isSingular()) {
				// the area is singular
				// => no further splits necessary
				if (areaCutData.innerAreas.isEmpty()) {
					// this area is finished and needs no further cutting
					finishedAreas.add(areaCutData.outerArea);
				} else {
					// read this area to further processing
					areasToCut.add(areaCutData);
				}
			} else {
				// we need to cut the area into two halves to get singular areas
				Rectangle r1 = cutPoint.getCutRectangleForArea(areaCutData.outerArea, true);
				Rectangle r2 = cutPoint.getCutRectangleForArea(areaCutData.outerArea, false);

				// Now find the intersection of these two boxes with the
				// original polygon. This will make two new areas, and each
				// area will be one (or more) polygons.
				Area a1 = areaCutData.outerArea;
				Area a2 = (Area) a1.clone();
				a1.intersect(new Area(r1));
				a2.intersect(new Area(r2));

				if (areaCutData.innerAreas.isEmpty()) {
					finishedAreas.addAll(Java2DConverter.areaToSingularAreas(a1));
					finishedAreas.addAll(Java2DConverter.areaToSingularAreas(a2));
				} else {
					ArrayList<Area> cuttedAreas = new ArrayList<Area>();
					cuttedAreas.addAll(Java2DConverter.areaToSingularAreas(a1));
					cuttedAreas.addAll(Java2DConverter.areaToSingularAreas(a2));
					
					for (Area nextOuterArea : cuttedAreas) {
						ArrayList<Area> nextInnerAreas = null;
						// go through all remaining inner areas and check if they
						// must be further processed with the nextOuterArea 
						for (Area nonProcessedInner : areaCutData.innerAreas) {
							if (nextOuterArea.intersects(nonProcessedInner.getBounds2D())) {
								if (nextInnerAreas == null) {
									nextInnerAreas = new ArrayList<Area>();
								}
								nextInnerAreas.add(nonProcessedInner);
							}
						}
						
						if (nextInnerAreas == null || nextInnerAreas.isEmpty()) {
							finishedAreas.add(nextOuterArea);
						} else {
							AreaCutData outCutData = new AreaCutData();
							outCutData.outerArea = nextOuterArea;
							outCutData.innerAreas= nextInnerAreas;
							areasToCut.add(outCutData);
						}
					}
				}
			}
			
		}
		
		// convert the java.awt.geom.Area back to the mkgmap way
		List<Way> cuttedOuterPolygon = new ArrayList<Way>(finishedAreas.size());
		for (Area area : finishedAreas) {
			Way w = singularAreaToWay(area, FakeIdGenerator.makeFakeId());
			if (w != null) {
				w.copyTags(outerPolygon);
				cuttedOuterPolygon.add(w);
				if (log.isDebugEnabled()) {
					log.debug("Way", outerPolygon.getId(), "splitted to way", w.getId());
				}
			}
		}

		return cuttedOuterPolygon;
	}

	

	/**
	 * Create the areas that are enclosed by the way. Usually the result should
	 * only be one area but some ways contain intersecting lines. To handle these
	 * erroneous cases properly the method might return a list of areas.
	 * 
	 * @param w a closed way
	 * @param clipBbox true if the areas should be clipped to the bounding box; false else
	 * @return a list of enclosed ares
	 */
	private List<Area> createAreas(Way w, boolean clipBbox) {
		Area area = Java2DConverter.createArea(w.getPoints());
		if (clipBbox && !bboxArea.contains(area.getBounds())) {
			// the area intersects the bounding box => clip it
			area.intersect(bboxArea);
		}
		List<Area> areaList = Java2DConverter.areaToSingularAreas(area);
		if (log.isDebugEnabled()) {
			log.debug("Bbox clipped way",w.getId()+"=>",areaList.size(),"distinct area(s).");
		}
		return areaList;
	}
	
	private CutPoint calcNextCutPoint(AreaCutData areaData) {
		if (areaData.innerAreas == null || areaData.innerAreas.isEmpty()) {
			return null;
		}
		
		if (areaData.innerAreas.size() == 1) {
			// make it short if there is only one inner area
			Rectangle outerBounds = areaData.outerArea.getBounds();
			CoordinateAxis axis = (outerBounds.width < outerBounds.height ? CoordinateAxis.LONGITUDE : CoordinateAxis.LATITUDE);
			CutPoint oneCutPoint = new CutPoint(axis);
			oneCutPoint.addArea(areaData.innerAreas.get(0));
			return oneCutPoint;
		}
		
		ArrayList<Area> innerStart = new ArrayList<Area>(
				areaData.innerAreas);
		
		ArrayList<CutPoint> bestCutPoints = new ArrayList<CutPoint>(CoordinateAxis.values().length);
		
		for (CoordinateAxis axis : CoordinateAxis.values()) {
			CutPoint bestCutPoint = new CutPoint(axis);
			CutPoint currentCutPoint = new CutPoint(axis);

			Collections.sort(innerStart, (axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START: COMP_LAT_START));

			for (Area anInnerStart : innerStart) {
				currentCutPoint.addArea(anInnerStart);

				if (currentCutPoint.compareTo(bestCutPoint) > 0) {
					bestCutPoint = currentCutPoint.duplicate();
				}
			}
			bestCutPoints.add(bestCutPoint);
		}

		return Collections.max(bestCutPoints);
		
	}

	/**
	 * Convert an area to an mkgmap way. The caller must ensure that the area is singular.
	 * Otherwise only the first part of the area is converted.
	 * 
	 * @param area
	 *            the area
	 * @param wayId
	 *            the wayid for the new way
	 * @return a new mkgmap way
	 */
	private Way singularAreaToWay(Area area, long wayId) {
		List<Coord> points = Java2DConverter.singularAreaToPoints(area);
		if (points == null || points.isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("Empty area", wayId + ".", mpRel.toBrowseURL());
			}
			return null;
		}

		return new Way(wayId, points);
	}

}
