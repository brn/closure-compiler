var goog = {

    };
/**
 * Inherit the prototype methods from one constructor into another.
 *
 * Usage:
 * <pre>
 * function ParentClass(a, b) { }
 * ParentClass.prototype.foo = function(a) { }
 *
 * function ChildClass(a, b, c) {
 *   goog.base(this, a, b);
 * }
 * goog.inherits(ChildClass, ParentClass);
 *
 * var child = new ChildClass('a', 'b', 'see');
 * child.foo(); // works
 * </pre>
 *
 * In addition, a superclass' implementation of a method can be invoked
 * as follows:
 *
 * <pre>
 * ChildClass.prototype.foo = function(a) {
 *   ChildClass.superClass_.foo.call(this, a);
 *   // other code
 * };
 * </pre>
 *
 * @param {Function} childCtor Child class.
 * @param {Function} parentCtor Parent class.
 */
goog.inherits = function(childCtor, parentCtor) {
  /** @constructor */
  function tempCtor() {};
  tempCtor.prototype = parentCtor.prototype;
  childCtor.superClass_ = parentCtor.prototype;
  childCtor.prototype = new tempCtor();
  /** @override */
  childCtor.prototype.constructor = childCtor;
};

camp.module("camp.injections", function (exports) {

  /**
   * @constructor
   */
  function AbstractClassMatcher() {}


  /**
   * @param {Function} classConstructor
   * @return {boolean}
   */
  AbstractClassMatcher.prototype.match = function(classConstructor) {
    return true;
  };


  /**
   * @constructor
   * @param {string} ns
   * @extends {AbstractClassMatcher}
   */
  function NamespaceMatcher(ns) {
    this._nsReg = new RegExp('^' + ns.replace(/\./g, '\\.') + '$');
  }
  goog.inherits(NamespaceMatcher, AbstractClassMatcher);


  NamespaceMatcher._cache = {};


  /**
   * @override
   * @param {Function} classConstructor
   * @return {boolean}
   */
  NamespaceMatcher.prototype.match = function(classConstructor) {
    var namespace = classConstructor.__qualifiedName__;
    var index = namespace.lastIndexOf('.');
    if (index > -1) {
      return this._nsReg.test(namespace.slice(0, index));
    }
    return this._nsReg.test(namespace);
  };


  /**
   * @constructor
   * @param {string} ns
   * @extends {AbstractClassMatcher}
   */
  function SubnamespaceMatcher(ns) {
    this._nsReg = new RegExp('^' + ns.replace(/\./g, '\\.'));
  }
  goog.inherits(SubnamespaceMatcher, AbstractClassMatcher);


  /**
   * @override
   * @param {Function} classConstructor
   * @returns {boolean}
   */
  SubnamespaceMatcher.prototype.match = function(classConstructor) {
    var namespace = classConstructor.__qualifiedName__;
    return this._nsReg.test(namespace);
  };



  /**
   * @constructor
   * @param {Function} classConstructor
   * @extends {AbstractClassMatcher}
   */
  function InstanceOfMatcher(classConstructor) {
    this._classConstructor = classConstructor;
  }
  goog.inherits(InstanceOfMatcher, AbstractClassMatcher);


  /**
   * @param {Function} classConstructor
   * @returns {boolean}
   */
  InstanceOfMatcher.prototype.match = function(classConstructor) {
    return classConstructor === this._classConstructor;
  };


  /**
   * @constructor
   * @param {Function} classConstructor
   * @extends {AbstractClassMatcher}
   */
  function SubclassMatcher(classConstructor) {
    this._classConstructor = classConstructor;
  }
  goog.inherits(SubclassMatcher, AbstractClassMatcher);


  /**
   * @param {Function} classConstructor
   * @returns {boolean}
   */
  SubclassMatcher.prototype.match = function(classConstructor) {
    return classConstructor.prototype instanceof this._classConstructor &&
      classConstructor !== this._classConstructor &&
      classConstructor.prototype !== this._classConstructor.prototype;
  };


  /**
   * @constructor
   */
  function AbstractMethodMatcher() {}


  /**
   * @param {Function} classConstructor
   * @return {Array.<string>}
   */
  AbstractMethodMatcher.prototype.match = function(classConstructor){
    return [''];
  };

  /**
   * @constructor
   * @extends {AbstractMethodMatcher}
   */
  function LikeMatcher(reg) {
    this._reg = new RegExp(reg.indexOf('^') === -1? '^' + reg : reg);
  }
  goog.inherits(LikeMatcher, AbstractMethodMatcher);


  /**
   * @inheritDoc
   */
  LikeMatcher.prototype.match = function(classConstructor) {
    var matched = [];
    var proto = classConstructor.prototype;
    for (var prop in proto) {
      if (this._reg.test(prop)) {
        matched.push(prop);
      }
    }
    return matched;
  };


  /**
   * @constructor
   */
  function AbstractAllMatcher(){}
  AbstractAllMatcher.prototype.match = function() {};


  /**
   * @constructor
   * @extends {AbstractAllMatcher}
   */
  function AnyMatcher() {}
  goog.inherits(AnyMatcher, AbstractAllMatcher);


  /**
   * @inheritDoc
   */
  AnyMatcher.prototype.match = function() {
    return true;
  };


  /**
   * @constructor
   */
  function _Matchers() {}

  /**
   * @param {string} ns
   * @returns {AbstractClassMatcher}
   */
  _Matchers.inNamespace = function(ns) {
    return new NamespaceMatcher(ns);
  };


  /**
   * @param {string} ns
   * @returns {SubnamespaceMatcher}
   */
  _Matchers.inSubnamespace = function(ns) {
    return new SubnamespaceMatcher(ns);
  };


  /**
   * @param {Function} classConstructor
   * @returns {AbstractClassMatcher}
   */
  _Matchers.instanceOf = function(classConstructor) {
    return new InstanceOfMatcher(classConstructor);
  };


  /**
   * @param {Function} classConstructor
   * @returns {AbstractClassMatcher}
   */
  _Matchers.subclassOf = function(classConstructor) {
    return new SubclassMatcher(classConstructor);
  };


  /**
   * @returns {AbstractAllMatcher}
   */
  _Matchers.any = function() {
    return new AnyMatcher();
  };


  /**
   * @param {string} reg
   * @returns {AbstractMethodMatcher}
   */
  _Matchers.like = function(reg) {
    return new LikeMatcher(reg.replace(/[\*\.]/g, function(match) {
      return match === '*'? '.*' : '\\.';
    }));
  };


  var Matchers = _Matchers;
  exports.Matchers = Matchers;
  exports.AbstractClassMatcher = AbstractClassMatcher;
  exports.AbstractMethodMatcher = AbstractMethodMatcher;
  exports.AbstractAllMatcher = AbstractAllMatcher;
});