/*
 * Copyright (C) 2006 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 11-Dec-2006
 */
package uk.me.parabola.imgfmt.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.mkgmap.filters.ShapeMergeFilter;
import uk.me.parabola.mkgmap.osmstyle.WrongAngleFixer;

/**
 * A point coordinate in extended map-units. A map unit is 360/2^24 degrees, we
 * use a higher resolution (see HIGH_PREC_BITS). In some places <i>shifted</i>
 * coordinates are used, which means that they are divided by some power of two
 * to save space in the file.
 *
 * You can create one of these with lat/long by calling the constructor with
 * double args.
 * 
 * See also http://www.movable-type.co.uk/scripts/latlong.html
 *
 * @author Steve Ratcliffe
 * @author Gerd Petermann
 */
public class Coord implements Comparable<Coord> {
	// GARMIN coord values 
	public static final int MAX_GARMIN_LONGITUDE = 1 << 23; //8388608
	public static final int MIN_GARMIN_LONGITUDE = -1 << 23; //-8388608
	public static final int MAX_GARMIN_LATITUDE = 1 << 22; // 4194304
	public static final int MIN_GARMIN_LATIITUDE = -1 << 22; //-4194304

	
	private static final short ON_BOUNDARY_MASK = 0x0001; // bit in flags is true if point lies on a boundary
	private static final short PRESERVED_MASK = 0x0002; // bit in flags is true if point should not be filtered out
	private static final short REPLACED_MASK = 0x0004;  // bit in flags is true if point was replaced 
	// 0x0008 currently unused
	private static final short FIXME_NODE_MASK = 0x0010; // bit in flags is true if a node with this coords has a fixme tag
	private static final short REMOVE_MASK = 0x0020; // bit in flags is true if this point should be removed
	private static final short VIA_NODE_MASK = 0x0040; // bit in flags is true if a node with this coords is the via node of a RestrictionRelation
	
	private static final short PART_OF_BAD_ANGLE = 0x0080; // bit in flags is true if point should be treated as a node
	private static final short PART_OF_SHAPE2 = 0x0100; // use only in ShapeMerger
	private static final short END_OF_WAY = 0x0200; // use only in WrongAngleFixer
	private static final short HOUSENUMBER_NODE = 0x0400; // start/end of house number interval
	private static final short ADDED_HOUSENUMBER_NODE = 0x0800; // node was added for house numbers
	
	private static final int HIGH_PREC_BITS = 30; // TODO : 31 or 32 cause overflow problems
	public static final int DELTA_SHIFT = HIGH_PREC_BITS - 24; 
	private static final long FACTOR_HP = 1L << HIGH_PREC_BITS;
	private static final int DELTA_HALF = 1 << (DELTA_SHIFT - 1);
	
	public static final double R = 6378137.0; // Radius of earth at equator as defined by WGS84
	public static final double U = R * 2 * Math.PI; // circumference of earth at equator (WGS84)
	public static final double MEAN_EARTH_RADIUS = 6371000; // earth is a flattened sphere
	
	private final int latHp;
	private final int lonHp;
	private byte highwayCount; // number of highways that use this point
	private short approxDistanceToDisplayedCoord = -1; // value is calculated in get method
	private short flags; // further attributes
	
	// for high precision to garmin calculations
	private byte posFlags; 
	private static final byte LAT_DEC = 0x1; // decrement latitude
	private static final byte LAT_INC = 0x2; // increment latitude
	private static final byte LON_DEC = 0x4; // decrement longitude 
	private static final byte LON_INC = 0x8; // increment longitude

	/**
	 * Construct from co-ordinates that are already in map-units.
	 * @param latitude24 The latitude in map units.
	 * @param longitude24 The longitude in map units.
	 */
	public Coord(int latitude24, int longitude24) {
		latHp = garminToHighPrec(latitude24);
		lonHp = garminToHighPrec(longitude24);
	}

	@SuppressWarnings("unused")
	public static int garminToHighPrec (int latLon24) {
		if (HIGH_PREC_BITS == 32 && latLon24 == 0x800000) {
			// catch overflow 
			return Integer.MAX_VALUE; 
		}
		return latLon24 << DELTA_SHIFT;
	}
	/**
	 * Construct from regular latitude and longitude.
	 * @param latitude The latitude in degrees.
	 * @param longitude The longitude in degrees.
	 */
	public Coord(double latitude, double longitude) {
		latHp = toHighPrec(latitude);
		lonHp = toHighPrec(longitude);
	}
	
	private Coord(int latHighPrec, int lonHighPrec, boolean highPrec) {
		latHp = latHighPrec;
		lonHp = lonHighPrec;
	}

	
	/**
	 * Factory for high precision values.
	 * @param latHighPrec latitude in high precision
	 * @param lonHighPrec longitude in high precision
	 * @return Coord instance
	 */
	public static Coord makeHighPrecCoord(int latHighPrec, int lonHighPrec){
		return new Coord(latHighPrec, lonHighPrec, true);
	}
	
	/**
	 * Construct from other coord instance, copies 
	 * the lat/lon values in high precision
	 * @param other
	 */
	public Coord(Coord other) {
		this.approxDistanceToDisplayedCoord = other.approxDistanceToDisplayedCoord;
		this.latHp = other.latHp;
		this.lonHp = other.lonHp;
		this.posFlags = other.posFlags;
	}

	/**
	 * @return latitude in Garmin (24 bit) precision.
	 */
	public int getLatitude() {
		int lat24 = (latHp + DELTA_HALF) >> DELTA_SHIFT;
		if ((posFlags & LAT_DEC) != 0)
			--lat24; 
		else if ((posFlags & LAT_INC) != 0)
			++lat24;
		return lat24;
	}

	/**
	 * @return longitude in Garmin (24 bit) precision
	 */
	@SuppressWarnings("unused")
	public int getLongitude() {
		int lon24;
		if (HIGH_PREC_BITS == 32 && lonHp == Integer.MAX_VALUE)
			lon24 = (lonHp >> DELTA_SHIFT) + 1;
		else 
			lon24 = (lonHp + DELTA_HALF) >> DELTA_SHIFT;
		if ((posFlags & LON_DEC) != 0)
			--lon24; 
		else if ((posFlags & LON_INC) != 0)
			++lon24;
		return lon24;
	}

	/**
	 * @return the route node id
	 */
	public int getId() {
		return 0;
	}

	public int getHighwayCount() {
		return highwayCount;
	}

	/**
	 * Increase the counter how many highways use this coord.
	 */
	public void incHighwayCount() {
		// don't let it wrap
		if(highwayCount < Byte.MAX_VALUE)
			++highwayCount;
	}

	/**
	 * Decrease the counter how many highways use this coord.
	 */
	public void decHighwayCount() {
		// don't let it wrap
		if(highwayCount > 0)
			--highwayCount;
	}
	
	/**
	 * Resets the highway counter to 0.
	 */
	public void resetHighwayCount() {
		highwayCount = 0;
	}
	
	public boolean getOnBoundary() {
		return (flags & ON_BOUNDARY_MASK) != 0;
	}

	public void setOnBoundary(boolean onBoundary) {
		if (onBoundary) 
			this.flags |= ON_BOUNDARY_MASK;
		else 
			this.flags &= ~ON_BOUNDARY_MASK; 
	}

	public boolean preserved() {
		return (flags & PRESERVED_MASK) != 0 || (flags & HOUSENUMBER_NODE) != 0;
	}

	public void preserved(boolean preserved) {
		if (preserved) 
			this.flags |= PRESERVED_MASK;
		else 
			this.flags &= ~PRESERVED_MASK; 
	}

	/**
	 * Returns if this coord was marked to be replaced in short arc removal.
	 * @return True means the replacement has to be looked up.
	 */
	public boolean isReplaced() {
		return (flags & REPLACED_MASK) != 0;
	}

	/**
	 * Mark a point as replaced in short arc removal process.
	 * @param replaced true or false
	 */
	public void setReplaced(boolean replaced) {
		if (replaced) 
			this.flags |= REPLACED_MASK;
		else 
			this.flags &= ~REPLACED_MASK; 
	}

	/**
	 * Does this coordinate belong to a node with a fixme tag?
	 * Note that the value is set after evaluating the points style. 
	 * @return true if the fixme flag is set, else false
	 */
	public boolean isFixme() {
		return (flags & FIXME_NODE_MASK) != 0;
	}
	
	public void setFixme(boolean b) {
		if (b) 
			this.flags |= FIXME_NODE_MASK;
		else 
			this.flags &= ~FIXME_NODE_MASK; 
	}
	
	public boolean isToRemove() {
		return (flags & REMOVE_MASK) != 0;
	}
	
	public void setRemove(boolean b) {
		if (b) 
			this.flags |= REMOVE_MASK;
		else 
			this.flags &= ~REMOVE_MASK; 
	}
	
	/**
	 * @return true if this coordinate belong to a via node of a restriction relation
	 */
	public boolean isViaNodeOfRestriction() {
		return (flags & VIA_NODE_MASK) != 0;
	}

	/**
	 * @param b true: Mark the coordinate as via node of a restriction relation
	 */
	public void setViaNodeOfRestriction(boolean b) {
		if (b) 
			this.flags |= VIA_NODE_MASK;
		else 
			this.flags &= ~VIA_NODE_MASK; 
	}
	
	/** 
	 * Should this Coord be treated by the removeWrongAngle method=
	 * The value has no meaning outside of StyledConverter.
	 * @return true if this coord is part of a line that has a big bearing error. 
	 */
	public boolean isPartOfBadAngle() {
		return (flags & PART_OF_BAD_ANGLE) != 0;
	}

	/**
	 * Mark the Coord to be part of a line which has a big bearing
	 * error because of the rounding to map units. 
	 * @param b true or false
	 */
	public void setPartOfBadAngle(boolean b) {
		if (b) 
			this.flags |= PART_OF_BAD_ANGLE;
		else 
			this.flags &= ~PART_OF_BAD_ANGLE; 
	}

	/** 
	 * Get flag for {@link ShapeMergeFilter}
	 * The value has no meaning outside of {@link ShapeMergeFilter}
	 * @return flag value
	 */
	public boolean isPartOfShape2() {
		return (flags & PART_OF_SHAPE2) != 0;
	}

	/**
	 * Set or unset flag for {@link ShapeMergeFilter} 
	 * @param b true or false
	 */
	public void setPartOfShape2(boolean b) {
		if (b) 
			this.flags |= PART_OF_SHAPE2;
		else 
			this.flags &= ~PART_OF_SHAPE2; 
	}

	/** 
	 * Get flag for {@link WrongAngleFixer}
	 * The value has no meaning outside of {@link WrongAngleFixer}
	 * @return flag value
	 */
	public boolean isEndOfWay() {
		return (flags & END_OF_WAY) != 0;
	}

	/**
	 * Set or unset flag for {@link WrongAngleFixer} 
	 * @param b true or false
	 */
	public void setEndOfWay(boolean b) {
		if (b) 
			this.flags |= END_OF_WAY;
		else 
			this.flags &= ~END_OF_WAY; 
	}

	/**
	 * @return if this is the beginning/end of a house number interval 
	 */
	public boolean isNumberNode(){
		return (flags & HOUSENUMBER_NODE) != 0;
	}
	
	/**
	 * @param b true or false
	 */
	public void setNumberNode(boolean b) {
		if (b) 
			this.flags |= HOUSENUMBER_NODE;
		else 
			this.flags &= ~HOUSENUMBER_NODE; 
	}
	
	/**
	 * @return if this is the beginning/end of a house number interval 
	 */
	public boolean isAddedNumberNode(){
		return (flags & ADDED_HOUSENUMBER_NODE) != 0;
	}
	
	/**
	 * @param b true or false
	 */
	public void setAddedNumberNode(boolean b) {
		if (b) 
			this.flags |= ADDED_HOUSENUMBER_NODE;
		else 
			this.flags &= ~ADDED_HOUSENUMBER_NODE; 
	}
	
	public int hashCode() {
		// Use a factor for latitude to span over the whole integer range:
		// max lat: 4194304
		// max lon: 8388608
		// max hashCode: 2118123520 < 2147483647 (Integer.MAX_VALUE)
		return 503 * getLatitude() + getLongitude();
	}

	/**
	 * Compares the coordinates that are displayed in the map
	 */
	public boolean equals(Object obj) {
		if (obj == null || !(obj instanceof Coord))
			return false;
		Coord other = (Coord) obj;
		return getLatitude() == other.getLatitude() && getLongitude() == other.getLongitude();
	}
	
	/**
	 * Compares the coordinates using the delta values. 
	 * XXX: Note that 
	 * p1.highPrecEquals(p2) is not always equal to p1.equals(p2)
	 * @param other
	 * @return
	 */
	public boolean highPrecEquals(Coord other) {
		if (other == null)
			return false;
		if (this == other)
			return true;
		return getHighPrecLat() == other.getHighPrecLat() && getHighPrecLon() == other.getHighPrecLon(); 
	} 

	/**
	 * Distance to other point in metres, using
	 * "flat earth approximation" or rhumb-line algo
	 */
	public double distance(Coord other) {
		double d1 = U / 360 * Math.sqrt(distanceInDegreesSquared(other));
		if (d1 < 10000)
			return d1; // error is below 0.01 m
		// for long distances, use more complex algorithm 
		return distanceOnRhumbLine(other);
	}

	/**
	 * Square of distance to other point in metres, using
	 * "flat earth approximation" 
	 */
	public double distanceInDegreesSquared(Coord other) {
		if (this == other || highPrecEquals(other))
			return 0;
		
		double lat1 = getLatDegrees();
		double lat2 = other.getLatDegrees();
		double long1 = getLonDegrees();
		double long2 = other.getLonDegrees();
				
		double latDiff;
		if (lat1 < lat2)
			latDiff = lat2 - lat1;
		else
			latDiff = lat1 - lat2;	
		if (latDiff > 90)
			latDiff -= 180;

		double longDiff;
		if (long1 < long2)
			longDiff = long2 - long1;
		else
			longDiff = long1 - long2;
		if (longDiff > 180)
			longDiff -= 360;

		// scale longDiff by cosine of average latitude
		longDiff *= Math.cos(Math.PI / 180 * Math.abs((lat1 + lat2) / 2));

		return (latDiff * latDiff) + (longDiff * longDiff);
	}
	
	/**
	 * Distance to other point in metres following a great circle path, without 
	 * flat earth approximation, slower but better with large 
	 * distances and big deltas in lat AND lon. 
	 * Similar to code in JOSM
	 */
	public double distanceHaversine (Coord point){
		double lat1 = hpToRadians(getHighPrecLat());
		double lat2 = hpToRadians(point.getHighPrecLat());
		double lon1 = hpToRadians(getHighPrecLon());
		double lon2 = hpToRadians(point.getHighPrecLon());
		double sinMidLat = Math.sin((lat1-lat2)/2);
		double sinMidLon = Math.sin((lon1-lon2)/2);
		double dRad = 2*Math.asin(Math.sqrt(sinMidLat*sinMidLat + Math.cos(lat1)*Math.cos(lat2)*sinMidLon*sinMidLon));
		double distance= dRad * R;
		return distance;
	}

	/**
	 * Distance to other point in metres following the shortest rhumb line.
	 */
	public double distanceOnRhumbLine(Coord point){
		double lat1 = hpToRadians(getHighPrecLat());
		double lat2 = hpToRadians(point.getHighPrecLat());
		double lon1 = hpToRadians(getHighPrecLon());
		double lon2 = hpToRadians(point.getHighPrecLon());
		
		// see http://williams.best.vwh.net/avform.htm#Rhumb

		double dLat = lat2 - lat1;
		double dLon = Math.abs(lon2 - lon1);
		// if dLon over 180° take shorter rhumb line across the anti-meridian:
		if (Math.abs(dLon) > Math.PI) dLon = dLon>0 ? -(2*Math.PI-dLon) : (2*Math.PI+dLon);

		// on Mercator projection, longitude distances shrink by latitude; q is the 'stretch factor'
		// q becomes ill-conditioned along E-W line (0/0); use empirical tolerance to avoid it
		double deltaPhi = Math.log(Math.tan(lat2/2+Math.PI/4)/Math.tan(lat1/2+Math.PI/4));
		double q = Math.abs(deltaPhi) > 10e-12 ? dLat/deltaPhi : Math.cos(lat1);

		// distance is pythagoras on 'stretched' Mercator projection
		double distRad = Math.sqrt(dLat*dLat + q*q*dLon*dLon); // angular distance in radians
		double dist = distRad * R;

		return dist;
	}

	/**
	 * Calculate point on the line this->other. If d is the distance between this and other,
	 * the point is {@code fraction * d} metres from this.
	 * For small distances between this and other we use a flat earth approximation,
	 * for large distances this could result in errors of many metres, so we use 
	 * the rhumb line calculations. 
	 */
	public Coord makeBetweenPoint(Coord other, double fraction) {
		int dlatHp = other.getHighPrecLat() - getHighPrecLat();
		int dlonHp = other.getHighPrecLon() - getHighPrecLon();
		if (dlonHp == 0 || Math.abs(dlatHp) < 1000000 && Math.abs(dlonHp) < 1000000 ){
			// distances are rather small, we can use flat earth approximation
			int latHighPrec = (int) (getHighPrecLat() + dlatHp * fraction);
			int lonHighPrec = (int) (getHighPrecLon() + dlonHp * fraction);
			return makeHighPrecCoord(latHighPrec, lonHighPrec);
		}
		double brng = this.bearingToOnRhumbLine(other, true);
		double dist = this.distance(other) * fraction;
		return this.destOnRhumLine(dist, brng);
	}

	
	/**
	 * returns bearing (in degrees) from current point to another point
	 * following a rhumb line
	 */
	public double bearingTo(Coord point) {
		return bearingToOnRhumbLine(point, false);
	}

	/**
	 * returns bearing (in degrees) from current point to another point
	 * following a great circle path
	 * @param point the other point
	 * @param needHighPrec set to true if you need a very high precision
	 */
	public double bearingToOnGreatCircle(Coord point, boolean needHighPrec) {
		// use high precision values for this 
		double lat1 = hpToRadians(getHighPrecLat());
		double lat2 = hpToRadians(point.getHighPrecLat());
		double lon1 = hpToRadians(getHighPrecLon());
		double lon2 = hpToRadians(point.getHighPrecLon());

		double dlon = lon2 - lon1;

		double y = Math.sin(dlon) * Math.cos(lat2);
		double x = Math.cos(lat1)*Math.sin(lat2) -
				Math.sin(lat1)*Math.cos(lat2)*Math.cos(dlon);
		double brngRad = needHighPrec ? Math.atan2(y, x) : Utils.atan2_approximation(y, x);
		return brngRad * 180 / Math.PI;
	}

	/**
	 * returns bearing (in degrees) from current point to another point
	 * following shortest rhumb line
	 * @param point the other point
	 * @param needHighPrec set to true if you need a very high precision
	 */
	public double bearingToOnRhumbLine(Coord point, boolean needHighPrec){
		double lat1 = hpToRadians(this.getHighPrecLat());
		double lat2 = hpToRadians(point.getHighPrecLat());
		double lon1 = hpToRadians(this.getHighPrecLon());
		double lon2 = hpToRadians(point.getHighPrecLon());

		double dLon = lon2-lon1;
		// if dLon over 180° take shorter rhumb line across the anti-meridian:
		if (Math.abs(dLon) > Math.PI) dLon = dLon>0 ? -(2*Math.PI-dLon) : (2*Math.PI+dLon);

		double deltaPhi = Math.log(Math.tan(lat2/2+Math.PI/4)/Math.tan(lat1/2+Math.PI/4));

		double brngRad = needHighPrec ? Math.atan2(dLon, deltaPhi) : Utils.atan2_approximation(dLon, deltaPhi);
		return brngRad * 180 / Math.PI;
	}

	
	/**
	 * Sort lexicographically by longitude, then latitude.
	 *
	 * This ordering is used for sorting entries in NOD3.
	 */
	public int compareTo(Coord other) {
		if (getLongitude() == other.getLongitude()) {
			if (getLatitude() == other.getLatitude())
				return 0;
			return getLatitude() > other.getLatitude() ? 1 : -1;
		}
		return getLongitude() > other.getLongitude() ? 1 : -1;
	}			

	/**
	 * Returns a string representation of the object.
	 *
	 * @return a string representation of the object.
	 */
	public String toString() {
		return (getLatitude()) + "/" + (getLongitude());
	}

	public String toDegreeString() {
		return String.format(Locale.ENGLISH, "%.6f,%.6f",
			getLatDegrees(),
			getLonDegrees());
	}

	protected String toOSMURL(int zoom) {
		return ("http://www.openstreetmap.org/?mlat=" +
				String.format(Locale.ENGLISH, "%.6f", getLatDegrees()) +
				"&mlon=" +
				String.format(Locale.ENGLISH, "%.6f", getLonDegrees()) +
				"&zoom=" +
				zoom);
	}

	public String toOSMURL() {
		return toOSMURL(17);
	}

	/**
	 * Convert latitude or longitude to HIGH_PREC_BITS bits value.
	 * This allows higher precision than the 24 bits
	 * used in map units.
	 * @param degrees The latitude or longitude as decimal degrees.
	 * @return An integer value with {@code HIGH_PREC_BITS} bit precision.
	 */
	public static int toHighPrec(double degrees) {
		return (int) Math.round(degrees / 360D * FACTOR_HP);
	}

	/* Factor for conversion to radians using HIGH_PREC_BITS bits
	 * (Math.PI / 180) * (360.0 / (1 << HIGH_PREC_BITS)) 
	 */
	private static final double HIGH_PREC_RAD_FACTOR = 2 * Math.PI / FACTOR_HP;
	
	/**
	 * Convert to radians using high precision 
	 * @param valHighPrec a longitude/latitude value with HIGH_PREC_BITS bit precision
	 * @return an angle in radians.
	 */
	public static double hpToRadians(int valHighPrec){
		return HIGH_PREC_RAD_FACTOR * valHighPrec;
	}

	/**
	 * @return Latitude from input data as signed HIGH_PREC_BITS bit integer. 
	 * When this instance was created from double values, the returned value 
	 * is as close as possible to the original (OSM / polish) position.  
	 */
	public int getHighPrecLat() {
		return latHp;
	}

	/**
	 * @return Longitude from input data as signed HIGH_PREC_BITS bit integer 
	 * When this instance was created from double values, the returned value 
	 * is as close as possible to the original (OSM / polish) position.  
	 */
	public int getHighPrecLon() {
		return lonHp;
	}
	
	/**
	 * @return latitude in degrees with highest avail. precision
	 */
	public double getLatDegrees(){
		return (360.0D / FACTOR_HP) * getHighPrecLat();
	}
	
	/**
	 * @return longitude in degrees with highest avail. precision
	 */
	public double getLonDegrees(){
		return (360.0D / FACTOR_HP) * getHighPrecLon();
	}
	
	public Coord getDisplayedCoord(){
		return new Coord(getLatitude(), getLongitude());
	}

	private int getLatDelta () {
		return latHp - (getLatitude() << DELTA_SHIFT);
	}

	private int getLonDelta () {
		return lonHp - (getLongitude() << DELTA_SHIFT);
	}

	/** gives the size of a bbox around the displayed coord */
	private static final int MAX_DELTA = 1 << (DELTA_SHIFT - 2); // max delta abs value that is considered okay
	/**
	 * Check if the rounding to 24 bit resolution caused large error. If so, the point may be placed
	 * at an alternative position. 
	 * @return true if rounding error is large.
	 */
	public boolean hasAlternativePos(){
		if (getOnBoundary())
			return false;
		int latDelta = getLatDelta();
		int lonDelta = getLonDelta();
		return (Math.abs(latDelta) > MAX_DELTA || Math.abs(lonDelta) > MAX_DELTA);
	}
	/**
	 * Calculate up to three points with equal 
	 * high precision coordinate, but
	 * different map unit coordinates. 
	 * @return a list of Coord instances, is empty if alternative positions are too far
	 */
	public List<Coord> getAlternativePositions(){
		ArrayList<Coord> list = new ArrayList<>();
		if (getOnBoundary())
			return list; 

		int latDelta = getLatDelta();
		int lonDelta = getLonDelta();
		
		boolean up = false;
		boolean down = false;
		boolean left = false;
		boolean right = false;
		
		if (latDelta > MAX_DELTA)
			up = true;
		else if (latDelta < -MAX_DELTA)
			down = true;
		if (lonDelta > MAX_DELTA)
			right= true;
		else if (lonDelta < -MAX_DELTA)
			left = true;
		if (down || up) {
			if (left || right) {
				Coord mod2 = new Coord(this);
				mod2.posFlags |= (left ? LON_DEC : LON_INC);
				mod2.posFlags |= (down ? LAT_DEC : LAT_INC);
				list.add(mod2);
			}
			Coord mod1 = new Coord(this);
			mod1.posFlags |= (down ? LAT_DEC : LAT_INC);
			list.add(mod1);
			
		}
		if (left || right) {
			Coord mod = new Coord(this);
			mod.posFlags |= (left ? LON_DEC : LON_INC);
			list.add(mod);
			
		}
		return list;
	}
	
	/**
	 * @return approximate distance in cm 
	 */
	public short getDistToDisplayedPoint(){
		if (approxDistanceToDisplayedCoord < 0){
			approxDistanceToDisplayedCoord = (short)Math.round(getDisplayedCoord().distance(this)*100);
		}
		return approxDistanceToDisplayedCoord;
	}
	
	/**
	 * Get the coord that is {@code dist} metre away travelling with course
	 * {@code brng} on a rhumb-line.
	 * @param dist distance in m
	 * @param brng bearing in degrees
	 * @return a new Coord instance
	 */
	public Coord destOnRhumLine(double dist, double brng){
		double distRad = dist / R; // angular distance in radians
		double lat1 = hpToRadians(this.getHighPrecLat());
		double lon1 = hpToRadians(this.getHighPrecLon());

		double brngRad = Math.toRadians(brng);

		double deltaLat = distRad * Math.cos(brngRad);

		double lat2 = lat1 + deltaLat;
		// check for some daft bugger going past the pole, normalise latitude if so
		if (Math.abs(lat2) > Math.PI/2) lat2 = lat2>0 ? Math.PI-lat2 : -Math.PI-lat2;
		double lon2;
		// catch special case: normalised value would be -8388608
		if (this.getLongitude() == MAX_GARMIN_LONGITUDE && brng == 0)
			lon2 = lon1;
		else { 
			double deltaPhi = Math.log(Math.tan(lat2/2+Math.PI/4)/Math.tan(lat1/2+Math.PI/4));
			double q = Math.abs(deltaPhi) > 10e-12 ? deltaLat / deltaPhi : Math.cos(lat1); // E-W course becomes ill-conditioned with 0/0

			double deltaLon = distRad*Math.sin(brngRad)/q;

			lon2 = lon1 + deltaLon;

			lon2 = (lon2 + 3*Math.PI) % (2*Math.PI) - Math.PI; // normalise to -180..+180º
		}

		return new Coord(Math.toDegrees(lat2), Math.toDegrees(lon2));
	}
	
	/**
	 * Calculate the distance in metres to the rhumb line
	 * defined by coords a and b.
	 * @param a start point
	 * @param b end point
	 * @return perpendicular distance in m.
	 */
	public double distToLineSegment(Coord a, Coord b){
		double ap = a.distance(this);
		double ab = a.distance(b);
		double bp = b.distance(this);
		if (ap == 0 || bp == 0)
			return 0;
		double abpa = (ab+ap+bp)/2;
		double dx = abpa-ab;
		double dist;
		if (dx < 0){
			// simple calculation using Herons formula will fail
			// calculate x, the point on line a-b which is as far away from a as this point
			double b_ab = a.bearingToOnRhumbLine(b, true);
			Coord x = a.destOnRhumLine(ap, b_ab);
			// this dist between these two points is not exactly 
			// the perpendicul distance, but close enough
			dist = x.distance(this);
		}
		else 
			dist = 2 * Math.sqrt(abpa * (abpa-ab) * (abpa-ap) * (abpa-bp)) / ab;
		return dist;
	}

	/**
	 * Calculate distance to rhumb line segment a-b.
	 * @param a point a
	 * @param b point b
	 * @return distance in m
	 */
	public double shortestDistToLineSegment(Coord a, Coord b){
		int aLon = a.getHighPrecLon();
		int bLon = b.getHighPrecLon();
		int pLon = this.getHighPrecLon();
		int aLat = a.getHighPrecLat();
		int bLat = b.getHighPrecLat();
		int pLat = this.getHighPrecLat();
		
		double deltaLon = bLon - aLon;
		double deltaLat = bLat - aLat;

		double frac;
		if (deltaLon == 0 && deltaLat == 0){ 
			frac = 0; 
		}
		else {
			// scale for longitude deltas by cosine of average latitude
			double scale = Math.cos(Coord.hpToRadians((aLat + bLat + pLat) / 3) );
			double deltaLonAP = scale * (pLon - aLon);
			deltaLon = scale * deltaLon;
			if (deltaLon == 0 && deltaLat == 0)
				frac = 0;
			else 
				frac = (deltaLonAP * deltaLon + (pLat - aLat) * deltaLat) / (deltaLon * deltaLon + deltaLat * deltaLat);
		}

		double distance;
		if (frac <= 0) {
			distance = a.distance(this);
		} else if (frac >= 1) {
			distance = b.distance(this);
		} else {
			distance = this.distToLineSegment(a, b);
		}
		return distance;
	}
	
	/**
	 * @return a new coordinate at the specified distance (metres) away along the specified bearing (degrees)
	 * uses "Destination point given distance and bearing from start point" formula from 
	 * http://www.movable-type.co.uk/scripts/latlong.html
	 */
	public Coord offset(double bearingInDegrees, double distanceInMetres) {
		double bearing = Math.toRadians(bearingInDegrees);
		double angularDistance = distanceInMetres / MEAN_EARTH_RADIUS;
		double lat = Math.toRadians(getLatDegrees());
		double lon = Math.toRadians(getLonDegrees());
		double newLat = Math.asin(Math.sin(lat) * Math.cos(angularDistance) + Math.cos(lat) * Math.sin(angularDistance) * Math.cos(bearing));
		double newLon = lon + Math.atan2(Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat), Math.cos(angularDistance) - Math.sin(lat) * Math.sin(newLat));
		return new Coord(Math.toDegrees(newLat), Math.toDegrees(newLon));
	}
}
