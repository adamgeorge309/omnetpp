%module(directors="1") Common
%module java_pragmas

// covariant return type warning disabled
#pragma SWIG nowarn=822

%include "std_function.i"
%std_function(MeasureTextFunctor, int, const char *);

%{
#include "common/stringutil.h"
#include "common/patternmatcher.h"
#include "common/matchexpression.h"
#include "common/javamatchableobject.h"
#include "common/unitconversion.h"
#include "common/quantityformatter.h"
#include "common/bigdecimal.h"
#include "common/rwlock.h"
#include "common/expression.h"
#include "common/exprvalue.h"
#include "common/fileutil.h"
#include "common/linetokenizer.h"

using namespace omnetpp::common;
%}

%include "defs.i"
%include "loadlib.i"
%include "pefileversion.i"
%include "std_string.i"
%include "std_vector.i"

namespace std {
   %typemap(javacode) vector<string> %{
       public String[] toArray() {
           int sz = (int) size();
           String[] array = new String[sz];
           for (int i=0; i<sz; i++)
               array[i] = get(i);
           return array;
       }
       public static StringVector fromArray(String[] array) {
           StringVector vector = new StringVector();
           for (int i=0; i<array.length; i++)
               vector.add(array[i]);
           return vector;
       }
   %}
}

namespace std {
   %template(StringVector) vector<string>;
   %template(PStringVector) vector<const char *>;
}

#define THREADED

// hide export/import macros from swig
#define COMMON_API
#define OPP_DLLEXPORT
#define OPP_DLLIMPORT
#define _OPP_GNU_ATTRIBUTE(x)

%include "std_set.i"     // our custom version
namespace std {
   %template(StringSet) set<string>;
};

namespace omnetpp { namespace common {

%rename(parseQuotedString)   opp_parsequotedstr;
%rename(quoteString)         opp_quotestr;
%rename(needsQuotes)         opp_needsquotes;
%rename(quoteStringIfNeeded) opp_quotestr_ifneeded;
%rename(formatDouble)        opp_formatdouble;

std::string opp_parsequotedstr(const char *txt);
std::string opp_quotestr(const std::string& txt);
bool opp_needsquotes(const char *txt);
std::string opp_quotestr_ifneeded(const std::string& txt);
int opp_strdictcmp(const char *s1, const char *s2);
std::string opp_formatdouble(double value, int numSignificantDigits);
//int getPEVersion(const char *fileName);

%ignore UnitConversion::parseQuantity(const char *, std::string&);

typedef int64_t intpar_t;

} } // namespaces

%typemap(out)     omnetpp::common::JavaMatchableObject * () {
  *(omnetpp::common::JavaMatchableObject **)&jresult = result;
  result->setJavaEnv(jenv);
}

%include "common/patternmatcher.h"
%include "common/matchexpression.h"
%include "common/javamatchableobject.h"
%include "common/unitconversion.h"
%include "common/quantityformatter.h"


/* -------------------- BigDecimal -------------------------- */

namespace omnetpp { namespace common {

%{
// some shitty Windows header file defines min()/max() macros that conflict with us
#undef min
#undef max
%}

%ignore _I64_MAX_DIGITS;
%ignore BigDecimal::BigDecimal();
%ignore BigDecimal::str(char*);
%ignore BigDecimal::parse(const char*,const char*&);
%ignore BigDecimal::ttoa;
%ignore BigDecimal::Nil;
%ignore BigDecimal::isNil;
%ignore BigDecimal::operator=;
%ignore BigDecimal::operator+=;
%ignore BigDecimal::operator-=;
%ignore BigDecimal::operator*=;
%ignore BigDecimal::operator/=;
%ignore BigDecimal::operator!=;
%ignore operator+;
%ignore operator-;
%ignore operator*;
%ignore operator/;
%ignore operator<<;
%immutable BigDecimal::Zero;
%immutable BigDecimal::NaN;
%immutable BigDecimal::PositiveInfinity;
%immutable BigDecimal::NegativeInfinity;
%rename(equals) BigDecimal::operator==;
%rename(less) BigDecimal::operator<;
%rename(greater) BigDecimal::operator>;
%rename(lessOrEqual) BigDecimal::operator<=;
%rename(greaterOrEqual) BigDecimal::operator>=;
%rename(toString) BigDecimal::str;
%rename(doubleValue) BigDecimal::dbl;

/*
FIXME new swig errors:
/home/andras/omnetpp/src/common/bigdecimal.h:120: Warning 503: Can't wrap 'operator +' unless renamed to a valid identifier.
/home/andras/omnetpp/src/common/bigdecimal.h:121: Warning 503: Can't wrap 'operator -' unless renamed to a valid identifier.
/home/andras/omnetpp/src/common/bigdecimal.h:123: Warning 503: Can't wrap 'operator *' unless renamed to a valid identifier.
/home/andras/omnetpp/src/common/bigdecimal.h:124: Warning 503: Can't wrap 'operator *' unless renamed to a valid identifier.
/home/andras/omnetpp/src/common/bigdecimal.h:125: Warning 503: Can't wrap 'operator /' unless renamed to a valid identifier.
/home/andras/omnetpp/src/common/bigdecimal.h:126: Warning 503: Can't wrap 'operator /' unless renamed to a valid identifier.
*/

%extend BigDecimal {
   const BigDecimal add(const BigDecimal& x) {return *self + x;}
   const BigDecimal subtract(const BigDecimal& x) {return *self - x;}
}

SWIG_JAVABODY_METHODS(public, public, BigDecimal)

%typemap(javainterfaces) BigDecimal "Comparable<BigDecimal>"

%typemap(javacode) BigDecimal %{

    public boolean equals(Object other) {
       return other instanceof BigDecimal && equals((BigDecimal)other);
    }

    public int hashCode() {
       return (int)getIntValue();
    }

    public java.math.BigDecimal toBigDecimal() {
       long intVal = getIntValue();
       int scale = getScale();
       java.math.BigDecimal d = new java.math.BigDecimal(intVal);
       return (scale == 0 ? d : d.movePointRight(scale));
    }

    @Override
    public int compareTo(BigDecimal arg0) {
        if (greater(arg0))
            return 1;
        else if (less(arg0))
            return -1;
        else
            return 0;
    }
%}

} } // namespaces

const omnetpp::common::BigDecimal floor(const omnetpp::common::BigDecimal& x);
const omnetpp::common::BigDecimal ceil(const omnetpp::common::BigDecimal& x);
const omnetpp::common::BigDecimal fabs(const omnetpp::common::BigDecimal& x);
const omnetpp::common::BigDecimal fmod(const omnetpp::common::BigDecimal& x, const omnetpp::common::BigDecimal& y);
const omnetpp::common::BigDecimal max(const omnetpp::common::BigDecimal& x, const omnetpp::common::BigDecimal& y);
const omnetpp::common::BigDecimal min(const omnetpp::common::BigDecimal& x, const omnetpp::common::BigDecimal& y);

%include "common/bigdecimal.h"


/* -------------------- rwlock.h -------------------------- */

namespace omnetpp { namespace common {

%ignore ReaderMutex;
%ignore WriterMutex;
SWIG_JAVABODY_METHODS(public, public, ILock)

} } // namespaces

%include "common/rwlock.h"

/* -------------------- exprvalue.h -------------------------- */

namespace omnetpp { namespace common { namespace expression {

class ExprValue 
{
  public:
    enum Type {UNDEF=0, BOOL='B', INT='L', DOUBLE='D', STRING='S', POINTER='O'};

  public:
    Type getType() const;
    static const char *getTypeName(Type t);
    bool isNumeric() const;
    std::string str() const;
    bool boolValue() const;
    intval_t intValue() const;
    intval_t intValueInUnit(const char *targetUnit) const;
    double doubleValue() const;
    double doubleValueInUnit(const char *targetUnit) const;
    const char *getUnit() const;
    const char *stringValue() const;
};

} } } // namespaces


/* -------------------- expression.h -------------------------- */

namespace omnetpp { namespace common {

%ignore Expression::AstNode;
%ignore Expression::AstTranslator;
%ignore Expression::MultiAstTranslator;
%ignore Expression::BasicAstTranslator;
%ignore Expression::installAstTranslator;
%ignore Expression::getInstalledAstTranslators;
%ignore Expression::getDefaultAstTranslator;
%ignore Expression::setExpressionTree;
%ignore Expression::getExpressionTree;
%ignore Expression::removeExpressionTree;
%ignore Expression::parseToAst;
%ignore Expression::translateToExpressionTree;
%ignore Expression::performConstantFolding;
%ignore Expression::parseAndTranslate;
%ignore Expression::dumpTree;
%ignore Expression::dumpAst;
%ignore Expression::dumpAst;
%ignore Expression::dumpExprTree;

} } // namespaces

%include "common/expression.h"

/* -------------------- fileutil.h -------------------------- */

namespace omnetpp { namespace common {

// only wrap the following ones, for other functions we have java.io.File
std::vector<std::string> collectFilesInDirectory(const char *foldername, bool deep, const char *suffix=nullptr);
std::vector<std::string> collectMatchingFiles(const char *globstarPattern);

} } // namespaces

/* -------------------- linetokenizer.h -------------------------- */

%include "common/linetokenizer.h"
