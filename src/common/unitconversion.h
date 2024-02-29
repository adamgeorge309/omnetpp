//=========================================================================
//  UNITCONVERSION.H - part of
//                  OMNeT++/OMNEST
//           Discrete System Simulation in C++
//
//=========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 2006-2017 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `license' for details on this and other legal matters.
*--------------------------------------------------------------*/

#ifndef __OMNETPP_COMMON_UNITCONVERSION_H
#define __OMNETPP_COMMON_UNITCONVERSION_H

#include <vector>
#include "commondefs.h"
#include "exception.h"

namespace omnetpp {
namespace common {

/**
 * Unit conversion utilities. This class has built-in knowledge of some
 * physical units (seconds, watts, etc); see internal unitTable[].
 */
class COMMON_API UnitConversion
{
  protected:
    enum Mapping { LINEAR, LOG10 };
    struct Unit {
        const char *name;
        double mult;
        Mapping mapping;
        const char *baseUnitName;
        const char *longName;
        const char *bestUnitCandidatesList="";
        const Unit *baseUnit;
        std::vector<const Unit*> bestUnitCandidates;
    };
    static const Unit unitTable[];

    static const int HASHTABLESIZE = 2048; // must be power of 2
    static const Unit *hashTable[HASHTABLESIZE];
    static int numCollisions;

  public:
    // internal
    static void init();

  protected:
    static unsigned hashCode(const char *unitName);
    static bool matches(const Unit *unit, const char *unitName);
    static void insert(const char *key, const Unit *unit);
    static void fillHashtable();
    static void fillUnitData();

    static const Unit *lookupUnit(const char *unit);
    static bool readNumber(const char *&s, double& number);
    static bool readUnit(const char *&s, std::string& unit);
    static double convertToBase(double value, const Unit *unit);
    static double convertFromBase(double value, const Unit *unit);
    static double tryConvert(double d, const Unit *unit, const Unit *targetUnit);
    static void cannotConvert(const char *unit, const char *targetUnit);
    static double tryGetConversionFactor(const Unit *unit, const Unit *targetUnit);

  private:
    // all methods are static, no reason to instantiate
    UnitConversion() {}

  public:
    /**
     * Invokes parseQuantity(), and converts the result into the given unit.
     * nullptr or empty string for expectedUnit means no unit (dimensionless
     * number expected). If conversion is not possible (unrelated or unknown units),
     * an exception is thrown.
     */
    static double parseQuantity(const char *str, const char *expectedUnit=nullptr);

    /**
     * Converts a quantity given as string to a double, and returns it, together
     * with the unit it was given in. If there are several numbers and units
     * (see syntax), everything is converted into the last unit.
     *
     * Syntax: <number> | (<number> <unit>)+
     *
     * To reduce the chance of confusion, units must be in strictly decreasing order
     * of magnitude, and negative numbers may only occur at the start of the quantity.
     * The potential minus sign refers to the whole quantity. For example, "-1s200ms"
     * means -1.2s and not -0.8s; and "1s-100ms" is illegal.
     *
     * If there is a syntax error, or if unit mismatch is found (i.e. distance
     * is given instead of time), the method throws an exception.
     */
    static double parseQuantity(const char *str, std::string& outActualUnit);

    /**
     * Parse a quantity and return its unit. This method simply delegates to
     * parseQuantity(), and it only exists to make it possible to get the unit
     * from Java code.
     */
    static std::string parseQuantityForUnit(const char *str) {std::string u; parseQuantity(str, u); return u;}

    /**
     * Returns a concatenation of the number and the unit.
     */
    static std::string formatQuantity(double d, const char *unit=nullptr);

    /**
     * Returns a descriptive name of the given unit; in the worst case
     * it returns the input string itself in quotes.
     */
    static std::string getUnitDescription(const char *unit);

    /**
     * Returns a short string describing the conversion between the unit and its base unit.
     */
    static std::string getConversionDescription(const char *unit);

    /**
     * Returns 0.0 if conversion is not possible (unrelated or unrecognized units)
     * or if it the relationship is nonlinear (such as between dBW and W).
     */
    static double getConversionFactor(const char *sourceUnit, const char *targetUnit);

    /**
     * Converts the given value with unit into the given target unit. nullptr or empty
     * string for either argument means no unit (dimensionless number).
     * Throws an error if the conversion is not possible.
     */
    static double convertUnit(double d, const char *unit, const char *targetUnit);

    /**
     * Returns the best unit for human consumption for the given quantity. Returns
     * the unit in which the value is closest to 1.0 but >= 1.0 if at all possible.
     */
    static const char *getBestUnit(double d, const char *unit);

    /**
     * Returns the long name for the given unit, or nullptr if it is unrecognized.
     * See getAllUnits().
     */
    static const char *getLongName(const char *unit);

    /**
     * Returns the base unit for the given unit, or nullptr if the unit is unrecognized.
     * See getAllUnits().
     */
    static const char *getBaseUnit(const char *unit);

    /**
     * Returns true if the given string is a recognized measurement unit.
     */
    static bool isKnownUnit(const char *unit) {return lookupUnit(unit) != nullptr;}

    /**
     * Returns true if the given unit is linear (e.g. W and KiB), and false
     * if not (dBm, dbW). Unknown (custom) units are assumed to be linear.
     */
    static bool isLinearUnit(const char *unit);

    /**
     * Produces the list of all recognized units, with their short names.
     */
    static std::vector<const char *> getKnownUnits();
};

}  // namespace common
}  // namespace omnetpp

#endif

