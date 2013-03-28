camp.module("camp.dependencies", function (exports) {
  var RefrectionUtil = camp.using('camp.utils.RefrectionUtil');

  /**
   * @constructor
   */
  function AbstractClassMatcher() {}


  /**
   * @param {Function} classConstructor
   */
  AbstractClassMatcher.prototype.match = function(classConstructor) {};


  /**
   * @constructor
   * @param {string} ns
   */
  function NamespaceMatcher(ns) {
    this._nsReg = new RegExp('^' + ns.replace(/\./g, '\\.'));
  }
  goog.inherits(NamespaceMatcher, AbstractClassMatcher);


  NamespaceMatcher._cache = {};


  /**
   * @inheritDoc
   */
  NamespaceMatcher.prototype.match = function(classConstructor) {
    return this._nsReg.test(RefrectionUtil.getClassName(classConstructor));
  };



  /**
   * @constructor
   * @param {Function} classConstructor
   */
  function InstanceOfMatcher(classConstructor) {
    this._classConstructor = classConstructor;
  }
  goog.inherits(InstanceOfMatcher, AbstractClassMatcher);


  /**
   * @inheritDoc
   */
  InstanceOfMatcher.prototype.match = function(classConstructor) {
    return classConstructor === this._classConstructor;
  };


  /**
   * @constructor
   * @param {Function} classConstructor
   */
  function SubclassMatcher(classConstructor) {
    this._classConstructor = classConstructor;
  }
  goog.inherits(SubclassMatcher, AbstractClassMatcher);


  /**
   * @inheritDoc
   */
  SubclassMatcher.prototype.match = function(classConstructor) {
    return classConstructor.prototype instanceof this._classConstructor && classConstructor !== this._classConstructor;
  };


  /**
   * @constructor
   */
  function Matcher() {}

  Matcher.inNamaspace = function(ns) {
    return new NamespaceMatcher(ns);
  };

  Matcher.instanceOf = function(classConstructor) {
    return new InstanceOfMatcher(classConstructor);
  };

  Matcher.subclassOf = function(classConstructor) {
    return new SubclassMatcher(classConstructor);
  };


  /**
   * @constructor
   */
  function AbstractMethodMatcher() {}


  /**
   * @param {Function} classConstructor
   */
  AbstractMethodMatcher.prototype.match = function(classConstructor){};

  /**
   * @constructor
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


  Matcher.like = function(reg) {
    return new LikeMatcher(reg.replace(/[\*\.]/g, function(match) {
      return match === '*'? '.*' : '\\.';
    }));
  };


  /**
   * @enum {string}
   */
  Matcher.JointPoint = {
    AFTER : 'after',
    BEFORE : 'before'
  };



  exports.Matcher = Matcher;
  exports.AbstractClassMatcher = AbstractClassMatcher;
  exports.AbstractMethodMatcher = AbstractMethodMatcher;
});