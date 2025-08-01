// SPDX-License-Identifier: Apache-2.0

package chisel3

import chisel3.experimental.VecLiterals.AddVecLiteralConstructor
import chisel3.experimental.dataview.{isView, reify, reifyIdentityView, InvalidViewException}

import scala.collection.immutable.{SeqMap, VectorMap}
import scala.collection.mutable.{HashSet, LinkedHashMap}
import chisel3.experimental.{BaseModule, BundleLiteralException, HasTypeAlias, OpaqueType, VecLiteralException}
import chisel3.experimental.{requireIsChiselType, requireIsHardware, SourceInfo, UnlocatableSourceInfo}
import chisel3.internal._
import chisel3.internal.binding._
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl.ir._
import chisel3.reflect.DataMirror
import _root_.firrtl.{ir => fir}

import java.lang.Math.{floor, log10, pow}
import scala.collection.mutable

class AliasedAggregateFieldException(message: String) extends chisel3.ChiselException(message)

/** An abstract class for data types that solely consist of (are an aggregate
  * of) other Data objects.
  */
sealed trait Aggregate extends Data {

  protected def checkingLitOption(checkForDontCares: Boolean): Option[BigInt] = {
    // Shift the accumulated value by our width and add in our component, masked by our width.
    def shiftAdd(elt: Data, accumulator: Option[BigInt]): Option[BigInt] = {
      (accumulator, elt.litOption) match {
        case (Some(accumulator), Some(eltLit)) =>
          val width = elt.width.get
          val masked = ((BigInt(1) << width) - 1) & eltLit // also handles the negative case with two's complement
          Some((accumulator << width) + masked)
        case (Some(accumulator), None) if checkForDontCares =>
          Builder.error(s"Called litValue on aggregate $this contains DontCare")(UnlocatableSourceInfo)
          None
        case (None, _) => None
        case (_, None) => None
      }
    }

    topBindingOpt match {
      // Don't accidentally invent a literal value for a view that is empty
      case Some(_: AggregateViewBinding) if this.getElements.isEmpty =>
        reifyIdentityView(this) match {
          case Some((target: Aggregate, _)) => target.checkingLitOption(checkForDontCares)
          // This can occur with empty Vecs or Bundles
          case _ => None
        }
      case Some(_: BundleLitBinding | _: VecLitBinding | _: AggregateViewBinding) =>
        // Records store elements in reverse order and higher indices are more significant in Vecs
        this.getElements.foldRight(Option(BigInt(0)))(shiftAdd)
      case _ => None
    }
  }

  /** Return an Aggregate's literal value if it is a literal, None otherwise.
    * If any element of the aggregate is not a literal (or DontCare), the result isn't a literal.
    *
    * @note [[DontCare]] is allowed and will be replaced with 0. Use [[litValue]] to disallow DontCare.
    * @return an Aggregate's literal value if it is a literal, None otherwise.
    */
  override def litOption: Option[BigInt] = {
    checkingLitOption(checkForDontCares = false)
  }

  /** Return an Aggregate's literal value if it is a literal, otherwise an exception is thrown.
    * If any element of the aggregate is not a literal with a defined width, the result isn't a literal.
    *
    * @return an Aggregate's literal value if it is a literal, exception otherwise.
    */
  override def litValue: BigInt = {
    checkingLitOption(checkForDontCares = true).getOrElse(
      throw new ChiselException(s"Cannot ask for litValue of $this as it is not a literal.")
    )
  }

  /** Returns a Seq of the immediate contents of this Aggregate, in order.
    */
  def getElements: Seq[Data]

  /** Save this result, as it can be very expensive to determine this otherwise */
  private[chisel3] lazy val elementsContainProbe: Boolean =
    elementsIterator.exists(d => chisel3.internal.containsProbe(d))

  /** Similar to [[getElements]] but allows for more optimized use */
  private[chisel3] def elementsIterator: Iterator[Data]

  private[chisel3] def width: Width = elementsIterator.map(_.width).foldLeft(0.W)(_ + _)

  // Emits the FIRRTL `this <= that`, or `this is invalid` if that == DontCare
  private[chisel3] def firrtlConnect(that: Data)(implicit sourceInfo: SourceInfo): Unit = {
    // If the source is a DontCare, generate a DefInvalid for the sink, otherwise, issue a Connect.
    if (that == DontCare) {
      pushCommand(DefInvalid(sourceInfo, lref))
    } else {
      pushCommand(Connect(sourceInfo, lref, Node(that)))
    }
  }

  // Due to prior lack of zero-width wire support, .asUInt for an empty Aggregate has returned 0.U (equivalent to 0.U(1.W))
  // In the case where an empty Aggregate is a child of an outer Aggregate, however, it would flatten out the empty inner Aggregate
  // This means we need the `first` argument so that we can preserve this behavior of Aggregates while still allowing subclasses
  // to override .asUInt behavior
  override private[chisel3] def _asUIntImpl(first: Boolean)(implicit sourceInfo: SourceInfo): UInt = {
    checkingLitOption(checkForDontCares = false) match {
      case Some(value) =>
        // Using UInt.Lit instead of .U so we can use Width argument which may be Unknown
        UInt.Lit(value, this.width)
      case None =>
        val elts = this.getElements.map(_._asUIntImpl(false))
        if (elts.isEmpty && !first) 0.U(0.W) else SeqUtils.asUInt(elts)
    }
  }

  // Return a literal of the same type from a Seq of literals for each leaf
  private[chisel3] final def _makeLitFromLeaves(elems: Seq[Element])(implicit sourceInfo: SourceInfo): Data = {
    // We could use virtual methods instead of matching on concrete subtypes,
    // but the difference for Record vs Vec is so small, it doesn't seem worth it
    val clone: Aggregate = this.cloneTypeFull
    val mapping = clone.flatten.view
      .zip(elems)
      .map { case (thisElt, litElt) =>
        val litArg = litElt.topBindingOpt match {
          case Some(ElementLitBinding(value)) => value
          case _ => throwException(s"Internal Error! For field $thisElt, given non-literal $litElt!")
        }
        thisElt -> litArg
      }
    val binding = clone match {
      case r: Record => BundleLitBinding(mapping.to(Map))
      case v: Vec[_] => VecLitBinding(mapping.to(VectorMap))
    }
    clone.bind(binding)
    clone
  }

  override protected def _fromUInt(that: UInt)(implicit sourceInfo: SourceInfo): Data = {
    val _asUInt = _resizeToWidth(that, this.widthOption, true)(identity)
    // If that is a literal and all constituent Elements can be represented as literals, return a literal
    val ((_, allLit), rvalues) = {
      this.flatten.toList.mapAccumulate[(Int, Boolean), Element]((0, _asUInt.isLit)) { case ((lo, literal), elt) =>
        val hi = lo + elt.getWidth
        // Chisel only supports zero width extraction if hi = -1 and lo = 0, so do it manually
        val _extracted = if (elt.getWidth == 0) 0.U(0.W) else _asUInt(hi - 1, lo)
        // _fromUInt returns Data but we know that it is an Element
        val rhs = elt._fromUIntPrivate(_extracted).asInstanceOf[Element]
        ((hi, literal && rhs.isLit), rhs)
      }
    }
    if (allLit) {
      this._makeLitFromLeaves(rvalues)
    } else {
      val _wire = Wire(this.cloneTypeFull)
      for ((l, r) <- _wire.flatten.zip(rvalues)) {
        l := r
      }
      _wire
    }
  }
}

trait VecFactory extends SourceInfoDoc {

  /** Creates a new [[Vec]] with `n` entries of the specified data type.
    *
    * @note elements are NOT assigned by default and have no value
    */
  def apply[T <: Data](n: Int, gen: T)(implicit sourceInfo: SourceInfo): Vec[T] = {
    requireIsChiselType(gen, "vec type")
    new Vec(gen.cloneTypeFull, n)
  }

  /** Truncate an index to implement modulo-power-of-2 addressing. */
  private[chisel3] def truncateIndex(
    idx: UInt,
    n:   BigInt
  )(
    implicit sourceInfo: SourceInfo
  ): UInt = {
    val w = (n - 1).bitLength
    if (n <= 1) WireInit(0.U) // Need the Wire otherwise we emit vec[0] which is illegal FIRRTL.
    // Other cases do not need a Wire because the literal is truncated to fit.
    else if (idx.width.known && idx.width.get <= w) idx
    else if (idx.width.known) idx(w - 1, 0)
    else (idx | 0.U(w.W))(w - 1, 0)
  }
}

/** A vector (array) of [[Data]] elements. Provides hardware versions of various
  * collection transformation functions found in software array implementations.
  *
  * Careful consideration should be given over the use of [[Vec]] vs
  * [[scala.collection.immutable.Seq Seq]] or some other Scala collection. In general [[Vec]] only
  * needs to be used when there is a need to express the hardware collection in a [[Reg]] or IO
  * [[Bundle]] or when access to elements of the array is indexed via a hardware signal.
  *
  * Example of indexing into a [[Vec]] using a hardware address and where the [[Vec]] is defined in
  * an IO [[Bundle]]
  *
  *  {{{
  *    val io = IO(new Bundle {
  *      val in = Input(Vec(20, UInt(16.W)))
  *      val addr = Input(UInt(5.W))
  *      val out = Output(UInt(16.W))
  *    })
  *    io.out := io.in(io.addr)
  *  }}}
  *
  * @tparam T type of elements
  *
  * @note
  *  - when multiple conflicting assignments are performed on a Vec element, the last one takes effect (unlike Mem, where the result is undefined)
  *  - Vecs, unlike classes in Scala's collection library, are propagated intact to FIRRTL as a vector type, which may make debugging easier
  */
sealed class Vec[T <: Data] private[chisel3] (gen: => T, val length: Int)
    extends Aggregate
    with VecIntf[T]
    with VecLike[T] {

  override def toString: String = {
    topBindingOpt match {
      case Some(VecLitBinding(vecLitBinding)) =>
        val contents = vecLitBinding.zipWithIndex.map { case ((data, lit), index) =>
          s"$index=$lit"
        }.mkString(", ")
        s"${sample_element.cloneType}[$length]($contents)"
      case _ => stringAccessor(s"${sample_element.cloneType}[$length]")
    }
  }

  /** Give this Vec a default, stable desired name using the supplied `Data`
    * generator's `typeName`
    */
  override def typeName = s"Vec${length}_${gen.typeName}"

  override def containsAFlipped = sample_element.containsAFlipped

  /** Save this result, as it can be very expensive to determine this otherwise */
  override private[chisel3] lazy val elementsContainProbe: Boolean = chisel3.internal.containsProbe(sample_element)

  override private[chisel3] def bind(target: Binding, parentDirection: SpecifiedDirection): Unit = {
    this.maybeAddToParentIds(target)
    binding = target

    val resolvedDirection = SpecifiedDirection.fromParent(parentDirection, specifiedDirection)
    sample_element.bind(SampleElementBinding(this), resolvedDirection)
    // Share same object for binding of all children.
    val childBinding = ChildBinding(this)
    for (child <- elementsIterator) { // assume that all children are the same
      child.bind(childBinding, resolvedDirection)
    }

    // Since all children are the same, we can just use the sample_element rather than all children
    direction =
      ActualDirection.fromChildren(Set(sample_element.direction), resolvedDirection).getOrElse(ActualDirection.Empty)
  }

  // Note: the constructor takes a gen() function instead of a Seq to enforce
  // that all elements must be the same and because it makes FIRRTL generation
  // simpler.
  private[chisel3] lazy val self: Seq[T] = {
    val _self = Vector.fill(length)(gen)
    val thisNode = Node(this) // Share the same Node for all elements.
    for ((elt, i) <- _self.zipWithIndex)
      elt.setRef(thisNode, i)
    _self
  }

  /**
    * sample_element 'tracks' all changes to the elements.
    * For consistency, sample_element is always used for creating dynamically
    * indexed ports and outputing the FIRRTL type.
    *
    * Needed specifically for the case when the Vec is length 0.
    */
  private[chisel3] val sample_element: T = gen

  // allElements current includes sample_element
  // This is somewhat weird although I think the best course of action here is
  // to deprecate allElements in favor of dispatched functions to Data or
  // a pattern matched recursive descent
  private[chisel3] final override def allElements: Seq[Element] =
    (sample_element +: self).flatMap(_.allElements)

  /** The "bulk connect operator", assigning elements in this Vec from elements in a Seq.
    *
    * For chisel3._, uses the `chisel3.internal.BiConnect` algorithm; sub-elements of `that` may end up driving sub-elements of `this`
    *  - Complicated semantics, will likely be deprecated in the future
    *
    * For Chisel._, emits the FIRRTL.<- operator
    *  - Equivalent to `this :<>= that` but bundle field names and vector sizes do not have to match
    *
    * @note the length of this Vec and that Seq must match
    * @param that the Seq to connect from
    * @group connection
    */
  def <>(that: Seq[T])(implicit sourceInfo: SourceInfo): Unit = {
    if (this.length != that.length)
      Builder.error(
        s"Vec (size ${this.length}) and Seq (size ${that.length}) being bulk connected have different lengths!"
      )
    for ((a, b) <- this.zip(that)) {
      a <> b
    }
  }

  /** The "bulk connect operator", assigning elements in this Vec from elements in a Vec.
    *
    * For chisel3._, uses the `chisel3.internal.BiConnect` algorithm; sub-elements of `that` may end up driving sub-elements of `this`
    *  - See docs/src/explanations/connection-operators.md for details
    *
    * For Chisel._, emits the FIRRTL.<- operator
    *  - Equivalent to `this :<>= that` without the restrictions that bundle field names and vector sizes must match
    *
    * @note This is necessary in [[Aggregate]], rather than relying on [[Data.<>]], due to supporting the Seq
    * @note the length of this Vec and that Vec must match
    * @param that the Vec to connect from
    * @group connection
    */
  def <>(that: Vec[T])(implicit sourceInfo: SourceInfo): Unit =
    this.bulkConnect(that.asInstanceOf[Data])

  /** "The strong connect operator", assigning elements in this Vec from elements in a Seq.
    *
    * For chisel3._, this operator is mono-directioned; all sub-elements of `this` will be driven by sub-elements of `that`.
    *  - Equivalent to `this :#= that`
    *
    * For Chisel._, this operator connections bi-directionally via emitting the FIRRTL.<=
    *  - Equivalent to `this :<>= that`
    *
    * @note the length of this Vec must match the length of the input Seq
    * @group connection
    */
  def :=(that: Seq[T])(implicit sourceInfo: SourceInfo): Unit = {
    require(
      this.length == that.length,
      s"Cannot assign to a Vec of length ${this.length} from a Seq of different length ${that.length}"
    )
    for ((a, b) <- this.zip(that))
      a := b
  }

  /** "The strong connect operator", assigning elements in this Vec from elements in a Vec.
    *
    * For chisel3._, this operator is mono-directioned; all sub-elements of `this` will be driven by sub-elements of `that`.
    *  - Equivalent to `this :#= that`
    *
    * For Chisel._, this operator connections bi-directionally via emitting the FIRRTL.<=
    *  - Equivalent to `this :<>= that`, with the additional restriction that the relative bundle field flips must match
    *
    * @note This is necessary in [[Aggregate]], rather than relying on [[Data.:=]], due to supporting the Seq
    * @note the length of this Vec must match the length of the input Vec
    * @group connection
    */
  def :=(that: Vec[T])(implicit sourceInfo: SourceInfo): Unit = this.connect(that)

  private[chisel3] def _applyImpl(p: UInt)(implicit sourceInfo: SourceInfo): T = {
    requireIsHardware(this, "vec")
    requireIsHardware(p, "vec index")

    // Don't bother with complex dynamic indexing logic when the index is a literal and therefore static
    // We also don't want to warn on literals that are "too small"
    p.litOption match {
      case Some(idx) if idx < length => return this.apply(idx.intValue)
      case _                         => // Fall through to control flow below
    }

    if (length == 0) {
      Builder.warning(Warning(WarningID.ExtractFromVecSizeZero, s"Cannot extract from Vec of size 0."))
    } else {
      p.widthOption.foreach { pWidth =>
        val correctWidth = BigInt(length - 1).bitLength
        def mkMsg(msg: String): String =
          s"Dynamic index with width $pWidth is too $msg for Vec of size $length (expected index width $correctWidth)."

        if (pWidth > correctWidth) {
          Builder.warning(Warning(WarningID.DynamicIndexTooWide, mkMsg("wide")))
        } else if (pWidth < correctWidth) {
          Builder.warning(Warning(WarningID.DynamicIndexTooNarrow, mkMsg("narrow")))
        }
      }
    }

    // Special handling for views
    if (isView(this)) {
      reifyIdentityView(this) match {
        // Views complicate things a bit, but views that correspond exactly to an identical Vec can just forward the
        // dynamic indexing to the target Vec
        // In theory, we could still do this forwarding if the sample element were different by deriving a DataView
        case Some((target: Vec[T @unchecked], _))
            if this.length == target.length &&
              this.sample_element.typeEquivalent(target.sample_element) =>
          return target.apply(p)
        case _ => throw InvalidViewException("Dynamic indexing of Views is not yet supported")
      }
    }

    val port = gen

    // Reconstruct the resolvedDirection (in Aggregate.bind), since it's not stored.
    // It may not be exactly equal to that value, but the results are the same.
    val reconstructedResolvedDirection = direction match {
      case ActualDirection.Input  => SpecifiedDirection.Input
      case ActualDirection.Output => SpecifiedDirection.Output
      case ActualDirection.Bidirectional.Default | ActualDirection.Unspecified =>
        SpecifiedDirection.Unspecified
      case ActualDirection.Bidirectional.Flipped => SpecifiedDirection.Flip
      case ActualDirection.Empty                 => SpecifiedDirection.Unspecified
      case dir                                   => throwException(s"Unexpected directionality: $dir")
    }
    // TODO port technically isn't directly child of this data structure, but the result of some
    // muxes / demuxes. However, this does make access consistent with the top-level bindings.
    // Perhaps there's a cleaner way of accomplishing this...
    port.bind(DynamicIndexBinding(this), reconstructedResolvedDirection)

    val i = Vec.truncateIndex(p, length)(UnlocatableSourceInfo)
    port.setRef(Node(this), i)

    port
  }

  /** Creates a statically indexed read or write accessor into the array.
    */
  def apply(idx: Int): T = self(idx)

  override def cloneType: this.type = {
    new Vec(gen.cloneTypeFull, length).asInstanceOf[this.type]
  }

  override def getElements: Seq[Data] = self

  final override private[chisel3] def elementsIterator: Iterator[Data] = self.iterator

  /** Default "pretty-print" implementation
    * Analogous to printing a Seq
    * Results in "Vec(elt0, elt1, ...)"
    */
  def toPrintable: Printable = {
    val elts =
      if (length == 0) List.empty[Printable]
      else self.flatMap(e => List(e.toPrintable, PString(", "))).dropRight(1)
    PString("Vec(") + Printables(elts) + PString(")")
  }

  private[chisel3] def _reduceTreeImpl(
    redOp:   (T, T) => T,
    layerOp: (T) => T = (x: T) => x
  )(
    implicit sourceInfo: SourceInfo
  ): T = {
    require(!isEmpty, "Cannot apply reduction on a vec of size 0")

    def recReduce[T](s: Seq[T], op: (T, T) => T, lop: (T) => T): T = {

      val n = s.length
      n match {
        case 1 => lop(s(0))
        case 2 => op(s(0), s(1))
        case _ =>
          val m = pow(2, floor(log10(n - 1) / log10(2))).toInt // number of nodes in next level, will be a power of 2
          val p = 2 * m - n // number of nodes promoted

          val l = s.take(p).map(lop)
          val r = s
            .drop(p)
            .grouped(2)
            .map { case Seq(a, b) =>
              op(a, b)
            }
            .toVector
          recReduce(l ++ r, op, lop)
      }
    }

    recReduce(this, redOp, layerOp)
  }

  /** Creates a Vec literal of this type with specified values. this must be a chisel type.
    *
    * @param elementInitializers literal values, specified as a pair of the Vec field to the literal value.
    * The Vec field is specified as a function from an object of this type to the field.
    * Fields that aren't initialized to DontCare, and assignment to a wire will overwrite any
    * existing value with DontCare.
    * @return a Vec literal of this type with subelement values specified
    *
    * Vec(2, UInt(8.W)).Lit(
    *   1 -> 0x0A.U,
    *   2 -> 0x0B.U
    * )
    * }}}
    */
  private[chisel3] def _makeLit(
    elementInitializers: (Int, T)*
  )(
    implicit sourceInfo: SourceInfo
  ): this.type = {

    def checkLiteralConstruction(): Unit = {
      val dupKeys = elementInitializers.map { x => x._1 }.groupBy(x => x).flatMap { case (k, v) =>
        if (v.length > 1) {
          Some(k, v.length)
        } else {
          None
        }
      }
      if (dupKeys.nonEmpty) {
        throw new VecLiteralException(
          s"VecLiteral: has duplicated indices ${dupKeys.map { case (k, n) => s"$k($n times)" }.mkString(",")}"
        )
      }

      val outOfRangeIndices = elementInitializers.map(_._1).filter { case index => index < 0 || index >= length }
      if (outOfRangeIndices.nonEmpty) {
        throw new VecLiteralException(
          s"VecLiteral: The following indices (${outOfRangeIndices.mkString(",")}) " +
            s"are less than zero or greater or equal to than Vec length"
        )
      }

      // look for literals of this vec that are wider than the vec's type
      val badLits = elementInitializers.flatMap {
        case (index, lit) =>
          (sample_element.width, lit.width) match {
            case (KnownWidth(m), KnownWidth(n)) =>
              if (m < n) Some(index -> lit) else None
            case (KnownWidth(_), _) =>
              None
            case (UnknownWidth, _) =>
              None
            case _ =>
              Some(index -> lit)
          }
        case _ => None
      }
      if (badLits.nonEmpty) {
        throw new VecLiteralException(
          s"VecLiteral: Vec[$gen] has the following incorrectly typed or sized initializers: " +
            badLits.map { case (a, b) => s"$a -> $b" }.mkString(",")
        )
      }

    }

    requireIsChiselType(this, "vec literal constructor model")
    checkLiteralConstruction()

    val clone: this.type = this.cloneType
    val cloneFields = getRecursiveFields(clone, "(vec root)").toMap

    // Create the Vec literal binding from litArgs of arguments
    val vecLitLinkedMap = new mutable.LinkedHashMap[Data, LitArg]()
    elementInitializers.sortBy { case (a, _) => a }.foreach { case (fieldIndex, value) =>
      val field = clone.apply(fieldIndex)
      val fieldName = cloneFields.getOrElse(
        field,
        throw new VecLiteralException(
          s"field $field (with value $value) is not a field," +
            s" ensure the field is specified as a function returning a field on an object of class ${this.getClass}," +
            s" eg '_.a' to select hypothetical bundle field 'a'"
        )
      )

      val valueBinding = value.topBindingOpt match {
        case Some(litBinding: LitBinding) => litBinding
        case _ => throw new VecLiteralException(s"field $fieldIndex specified with non-literal value $value")
      }

      field match { // Get the litArg(s) for this field
        case bitField: Bits =>
          if (!field.typeEquivalent(bitField)) {
            throw new VecLiteralException(
              s"VecLit: Literal specified at index $fieldIndex ($value) does not match Vec type $sample_element"
            )
          }
          if (bitField.getWidth > field.getWidth) {
            throw new VecLiteralException(
              s"VecLit: Literal specified at index $fieldIndex ($value) is too wide for Vec type $sample_element"
            )
          }
          val litArg = valueBinding match {
            case ElementLitBinding(litArg) => litArg
            case BundleLitBinding(litMap) =>
              litMap.getOrElse(
                value,
                throw new BundleLiteralException(s"Field $fieldName specified with unspecified value")
              )
            case VecLitBinding(litMap) =>
              litMap.getOrElse(
                value,
                throw new VecLiteralException(s"Field $fieldIndex specified with unspecified value")
              )
          }
          val adjustedLitArg = litArg.cloneWithWidth(sample_element.width)
          vecLitLinkedMap(bitField) = adjustedLitArg

        case recordField: Record =>
          if (!(recordField.typeEquivalent(value))) {
            throw new VecLiteralException(
              s"field $fieldIndex $recordField specified with non-type-equivalent value $value"
            )
          }
          // Copy the source BundleLitBinding with fields (keys) remapped to the clone
          val remap = getMatchedFields(value, recordField).toMap
          valueBinding.asInstanceOf[BundleLitBinding].litMap.map { case (valueField, valueValue) =>
            vecLitLinkedMap(remap(valueField)) = valueValue
          }

        case vecField: Vec[_] =>
          if (!(vecField.typeEquivalent(value))) {
            throw new VecLiteralException(
              s"field $fieldIndex $vecField specified with non-type-equivalent value $value"
            )
          }
          // Copy the source VecLitBinding with vecFields (keys) remapped to the clone
          val remap = getMatchedFields(value, vecField).toMap
          value.topBinding.asInstanceOf[VecLitBinding].litMap.map { case (valueField, valueValue) =>
            vecLitLinkedMap(remap(valueField)) = valueValue
          }

        case enumField: EnumType => {
          if (!(enumField.typeEquivalent(value))) {
            throw new VecLiteralException(
              s"field $fieldIndex $enumField specified with non-type-equivalent enum value $value"
            )
          }
          val litArg = valueBinding match {
            case ElementLitBinding(litArg) => litArg
            case _ =>
              throw new VecLiteralException(s"field $fieldIndex $enumField could not bematched with $valueBinding")
          }
          vecLitLinkedMap(field) = litArg
        }

        case _ => throw new VecLiteralException(s"unsupported field $fieldIndex of type $field")
      }
    }

    clone.bind(VecLitBinding(VectorMap(vecLitLinkedMap.toSeq: _*)))
    clone
  }
}

object Vec extends VecFactory

object VecInit extends VecInit$Intf {

  private[chisel3] def _applyImpl[T <: Data](elts: Seq[T])(implicit sourceInfo: SourceInfo): Vec[T] = {
    // REVIEW TODO: this should be removed in favor of the apply(elts: T*)
    // varargs constructor, which is more in line with the style of the Scala
    // collection API. However, a deprecation phase isn't possible, since
    // changing apply(elt0, elts*) to apply(elts*) causes a function collision
    // with apply(Seq) after type erasure. Workarounds by either introducing a
    // DummyImplicit or additional type parameter will break some code.

    // Check that types are homogeneous.  Width mismatch for Elements is safe.
    require(elts.nonEmpty, "Vec hardware values are not allowed to be empty")
    elts.foreach(requireIsHardware(_, "vec element"))

    val vec = Wire(Vec(elts.length, cloneSupertype(elts, "Vec")))

    for ((lhs, rhs) <- vec.zip(elts)) {
      lhs :<>= rhs
    }
    vec
  }

  private[chisel3] def _applyImpl[T <: Data](elt0: T, elts: T*)(implicit sourceInfo: SourceInfo): Vec[T] =
    _applyImpl(elt0 +: elts.toSeq)

  protected def _tabulateImpl[T <: Data](
    n: Int
  )(gen: (Int) => T)(
    implicit sourceInfo: SourceInfo
  ): Vec[T] =
    _applyImpl((0 until n).map(i => gen(i)))

  protected def _tabulateImpl[T <: Data](
    n: Int,
    m: Int
  )(gen: (Int, Int) => T)(
    implicit sourceInfo: SourceInfo
  ): Vec[Vec[T]] = {
    // TODO make this lazy (requires LazyList and cross compilation, beyond the scope of this PR)
    val elts = Seq.tabulate(n, m)(gen)
    val flatElts = elts.flatten

    require(flatElts.nonEmpty, "Vec hardware values are not allowed to be empty")
    flatElts.foreach(requireIsHardware(_, "vec element"))

    val tpe = cloneSupertype(flatElts, "Vec.tabulate")
    val myVec = Wire(Vec(n, Vec(m, tpe)))
    for {
      (xs1D, ys1D) <- myVec.zip(elts)
      (lhs, rhs) <- xs1D.zip(ys1D)
    } {
      lhs :<>= rhs
    }
    myVec
  }

  protected def _tabulateImpl[T <: Data](
    n: Int,
    m: Int,
    p: Int
  )(gen: (Int, Int, Int) => T)(
    implicit sourceInfo: SourceInfo
  ): Vec[Vec[Vec[T]]] = {
    // TODO make this lazy (requires LazyList and cross compilation, beyond the scope of this PR)
    val elts = Seq.tabulate(n, m, p)(gen)
    val flatElts = elts.flatten.flatten

    require(flatElts.nonEmpty, "Vec hardware values are not allowed to be empty")
    flatElts.foreach(requireIsHardware(_, "vec element"))

    val tpe = cloneSupertype(flatElts, "Vec.tabulate")
    val myVec = Wire(Vec(n, Vec(m, Vec(p, tpe))))

    for {
      (xs2D, ys2D) <- myVec.zip(elts)
      (xs1D, ys1D) <- xs2D.zip(ys2D)
      (lhs, rhs) <- xs1D.zip(ys1D)
    } {
      lhs :<>= rhs
    }

    myVec
  }

  protected def _fillImpl[T <: Data](n: Int)(gen: => T)(implicit sourceInfo: SourceInfo): Vec[T] =
    if (n == 0) { Wire(Vec(0, gen.cloneTypeFull)) }
    else { _applyImpl(Seq.fill(n)(gen)) }

  protected def _fillImpl[T <: Data](
    n: Int,
    m: Int
  )(gen: => T)(
    implicit sourceInfo: SourceInfo
  ): Vec[Vec[T]] = {
    _tabulateImpl(n, m)((_, _) => gen)
  }

  protected def _fillImpl[T <: Data](
    n: Int,
    m: Int,
    p: Int
  )(gen: => T)(
    implicit sourceInfo: SourceInfo
  ): Vec[Vec[Vec[T]]] = {
    _tabulateImpl(n, m, p)((_, _, _) => gen)
  }

  protected def _iterateImpl[T <: Data](
    start: T,
    len:   Int
  )(f: (T) => T)(
    implicit sourceInfo: SourceInfo
  ): Vec[T] =
    _applyImpl(Seq.iterate(start, len)(f))
}

/** A trait for [[Vec]]s containing common hardware generators for collection
  * operations.
  */
trait VecLike[T <: Data] extends VecLikeImpl[T] with IndexedSeq[T] with HasId {

  // IndexedSeq has its own hashCode/equals that we must not use
  override def hashCode:          Int = super[HasId].hashCode
  override def equals(that: Any): Boolean = super[HasId].equals(that)

  private[chisel3] def _forallImpl(p: T => Bool)(implicit sourceInfo: SourceInfo): Bool =
    (this.map(p)).fold(true.B)(_ && _)

  private[chisel3] def _existsImpl(p: T => Bool)(implicit sourceInfo: SourceInfo): Bool =
    (this.map(p)).fold(false.B)(_ || _)

  private[chisel3] def _containsImpl(x: T)(implicit sourceInfo: SourceInfo, ev: T <:< UInt): Bool =
    this._existsImpl(_ === x)

  private[chisel3] def _countImpl(p: T => Bool)(implicit sourceInfo: SourceInfo): UInt =
    SeqUtils.count(this.map(p))

  /** Helper function that appends an index (literal value) to each element,
    * useful for hardware generators which output an index.
    */
  private def indexWhereHelper(p: T => Bool) = this.map(p).zip((0 until length).map(i => i.asUInt))

  private[chisel3] def _indexWhereImpl(p: T => Bool)(implicit sourceInfo: SourceInfo): UInt =
    SeqUtils.priorityMux(indexWhereHelper(p))

  private[chisel3] def _lastIndexWhereImpl(p: T => Bool)(implicit sourceInfo: SourceInfo): UInt =
    SeqUtils.priorityMux(indexWhereHelper(p).reverse)

  private[chisel3] def _onlyIndexWhereImpl(p: T => Bool)(implicit sourceInfo: SourceInfo): UInt =
    SeqUtils.oneHotMux(indexWhereHelper(p))
}

/** Base class for Aggregates based on key values pairs of String and Data
  *
  * Record should only be extended by libraries and fairly sophisticated generators.
  * RTL writers should use [[Bundle]].  See [[Record#elements]] for an example.
  */
abstract class Record extends Aggregate with Selectable {

  /** The list of parameter accessors used in the constructor of this [[chisel3.Record]].
    *
    * @note This is automatically overridden via the compiler plugin for user-defined bundles that mix-in [[chisel3.experimental.HasAutoTypename]],
    *       and is meant for internal Chisel use only. Can not be manually overridden by users, or else an error will be thrown.
    * @note This lives in Record rather than the [[chisel3.experimental.HasAutoTypename]] trait, due to compiler implementation details
    *       preventing us from overriding a definition within a trait via the compiler plugin
    */
  protected def _typeNameConParams: Iterable[Any] = Vector.empty

  private[chisel3] def _isOpaqueType: Boolean = this match {
    case maybe: OpaqueType => maybe.opaqueType
    case _ => false
  }

  private def checkClone(clone: Record): Unit = {
    for ((name, field) <- _elements) {
      if (clone._elements(name) eq field) {
        throw new AutoClonetypeException(
          s"The bundle plugin was unable to clone $clone that has field '$name' aliased with base $this." +
            "This likely happened because you tried nesting Data arguments inside of other data structures." +
            " Try wrapping the field(s) in Input(...), Output(...), or Flipped(...) if appropriate." +
            " As a last resort, you can call chisel3.reflect.DataMirror.internal.chiselTypeClone on any nested Data arguments." +
            " See the cookbook entry 'How do I deal with the \"unable to clone\" error?' for more details."
        )
      }
    }
  }

  override def cloneType: this.type = {
    val clone: this.type = _cloneTypeImpl.asInstanceOf[this.type]
    checkClone(clone)
    clone
  }

  // Doing this earlier than onModuleClose allows field names to be available for prefixing the names
  // of hardware created when connecting to one of these elements
  private def setElementRefs(): Unit = {
    val opaqueType = this._isOpaqueType
    require(
      !opaqueType || (_elements.size == 1 && _elements.head._1 == ""),
      s"Opaque types must have exactly one element with an empty name, not ${_elements.size}: ${elements.keys.mkString(", ")}"
    )
    val thisNode = Node(this) // Share the same Node for all elements.
    // Names of _elements have already been namespaced (and therefore sanitized)
    for ((name, elt) <- _elements) {
      elt.setRef(thisNode, name, opaque = opaqueType)
    }
  }

  /** Checks that there are no duplicate elements (aka aliased fields) in the Record */
  private def checkForAndReportDuplicates(): Unit = {
    // Using List to avoid allocation in the common case of no duplicates
    var duplicates: List[Data] = Nil
    // Is there a more optimized datastructure we could use with the Int identities? BitSet? Requires benchmarking.
    val seen = mutable.HashSet.empty[Data]
    this.elementsIterator.foreach { e =>
      if (seen(e)) {
        duplicates = e :: duplicates
      }
      seen += e
    }
    if (!duplicates.isEmpty) {
      // show groups of names of fields with duplicate id's
      // The sorts make the displayed order of fields deterministic and matching the order of occurrence in the Bundle.
      // It's a bit convoluted but happens rarely and makes the error message easier to understand
      val dupNames = duplicates.toSeq
        .sortBy(_._id)
        .map { duplicate =>
          this._elements.collect { case x if x._2._id == duplicate._id => x }.toSeq
            .sortBy(_._2._id)
            .map(_._1)
            .reverse
            .mkString("(", ",", ")")
        }
        .mkString(",")
      throw new AliasedAggregateFieldException(
        s"${this.className} contains aliased fields named ${dupNames}"
      )
    }
  }

  /* Tracking variable for deciding Record flipped-ness. */
  private[chisel3] var _containsAFlipped: Boolean = false

  /* In the context of Records, containsAFlipped is assigned true if any of its children are flipped. */
  override def containsAFlipped: Boolean = _containsAFlipped

  override private[chisel3] def bind(target: Binding, parentDirection: SpecifiedDirection): Unit = {
    this.maybeAddToParentIds(target)
    binding = target

    val resolvedDirection = SpecifiedDirection.fromParent(parentDirection, specifiedDirection)

    checkForAndReportDuplicates()

    // Share same object for binding of all children.
    val childBinding = ChildBinding(this)
    for (((_, child), sameChild) <- this.elements.iterator.zip(this.elementsIterator)) {
      // This check is for making sure that elements always returns the
      // same object, which will not be the case if the user makes it a
      // def inside the Record. Checking elementsIterator against itself
      // is not useful for this check because it's a lazy val which will
      // always return the same thing.
      if (child != sameChild) {
        throwException(
          s"${this.className} does not return the same objects when calling .elements multiple times. Did you make it a def by mistake?"
        )
      }
      child.bind(childBinding, resolvedDirection)

      // Update the flipped tracker based on the flipped-ness of this specific child element
      _containsAFlipped |= child.containsAFlipped
    }

    // Check that children obey the directionality rules.
    val childDirections = elementsIterator.map(_.direction).toSet - ActualDirection.Empty
    direction = ActualDirection.fromChildren(childDirections, resolvedDirection) match {
      case Some(dir) => dir
      case None =>
        throwException(s"Internal Error! Unhandled directionality of children: $childDirections for $this!")
    }
    setElementRefs()

    this match {
      case aliasedRecord: HasTypeAlias => Builder.setRecordAlias(aliasedRecord, resolvedDirection)
      case _ =>
    }
  }

  /** Creates a Bundle literal of this type with specified values. this must be a chisel type.
    *
    * @param elems literal values, specified as a pair of the Bundle field to the literal value.
    * The Bundle field is specified as a function from an object of this type to the field.
    * Fields that aren't initialized to DontCare, and assignment to a wire will overwrite any
    * existing value with DontCare.
    * @return a Bundle literal of this type with subelement values specified
    *
    * @example {{{
    * class MyBundle extends Bundle {
    *   val a = UInt(8.W)
    *   val b = Bool()
    * }
    *
    * (new MyBundle).Lit(
    *   _.a -> 42.U,
    *   _.b -> true.B
    * )
    * }}}
    */
  private[chisel3] def _makeLit(elems: (this.type => (Data, Data))*)(implicit sourceInfo: SourceInfo): this.type = {

    requireIsChiselType(this, "bundle literal constructor model")
    val clone: this.type = cloneType
    val cloneFields = getRecursiveFields(clone, "_").toMap

    // Create the Bundle literal binding from litargs of arguments
    val bundleLitMapping = elems.map { fn => fn(clone) }.flatMap { case (field, value) =>
      val fieldName = cloneFields.getOrElse(
        field,
        throw new BundleLiteralException(
          s"field $field (with value $value) is not a field," +
            s" ensure the field is specified as a function returning a field on an object of class ${this.getClass}," +
            s" eg '_.a' to select hypothetical bundle field 'a'"
        )
      )
      val valueBinding = value.topBindingOpt match {
        case Some(litBinding: LitBinding) => litBinding
        case _ => throw new BundleLiteralException(s"field $fieldName specified with non-literal value $value")
      }

      field match { // Get the litArg(s) for this field
        case field: Bits =>
          if (field.getClass != value.getClass) { // TODO typeEquivalent is too strict because it checks width
            throw new BundleLiteralException(
              s"Field $fieldName $field specified with non-type-equivalent value $value"
            )
          }
          val litArg = valueBinding match {
            case ElementLitBinding(litArg) => litArg
            case BundleLitBinding(litMap) =>
              litMap.getOrElse(
                value,
                throw new BundleLiteralException(s"Field $fieldName specified with unspecified value")
              )
            case VecLitBinding(litMap) =>
              litMap.getOrElse(
                value,
                throw new VecLiteralException(s"Vec literal $fieldName specified with out literal values")
              )

          }
          Seq(field -> litArg)

        case field: Record =>
          if (!(field.typeEquivalent(value))) {
            throw new BundleLiteralException(
              s"field $fieldName $field specified with non-type-equivalent value $value"
            )
          }
          // Copy the source BundleLitBinding with fields (keys) remapped to the clone
          val remap = getMatchedFields(value, field).toMap
          value.topBinding.asInstanceOf[BundleLitBinding].litMap.map { case (valueField, valueValue) =>
            remap(valueField) -> valueValue
          }

        case vecField: Vec[_] =>
          if (!(vecField.typeEquivalent(value))) {
            throw new BundleLiteralException(
              s"field $fieldName $vecField specified with non-type-equivalent value $value"
            )
          }
          // Copy the source BundleLitBinding with fields (keys) remapped to the clone
          val remap = getMatchedFields(value, vecField).toMap
          value.topBinding.asInstanceOf[VecLitBinding].litMap.map { case (valueField, valueValue) =>
            remap(valueField) -> valueValue
          }

        case field: EnumType => {
          if (!(field.typeEquivalent(value))) {
            throw new BundleLiteralException(
              s"field $fieldName $field specified with non-type-equivalent enum value $value"
            )
          }
          val litArg = valueBinding match {
            case ElementLitBinding(litArg) => litArg
            case _ =>
              throw new BundleLiteralException(s"field $fieldName $field could not be matched with $valueBinding")
          }
          Seq(field -> litArg)
        }
        case _ => throw new BundleLiteralException(s"unsupported field $fieldName of type $field")
      }
    }

    // don't convert to a Map yet to preserve duplicate keys
    val duplicates = bundleLitMapping.map(_._1).groupBy(identity).collect { case (x, elts) if elts.size > 1 => x }
    if (!duplicates.isEmpty) {
      val duplicateNames = duplicates.map(cloneFields(_)).mkString(", ")
      throw new BundleLiteralException(s"duplicate fields $duplicateNames in Bundle literal constructor")
    }
    // Check widths and sign extend as appropriate.
    val bundleLitMap = bundleLitMapping.view.map { case (field, value) =>
      field.width match {
        // If width is unknown, then it is set by the literal value.
        case UnknownWidth => field -> value
        case width @ KnownWidth(widthValue) =>
          val valuex = if (widthValue < value.width.get) {
            // For legacy reasons, 0.U is 1-bit, don't warn when it comes up as a literal value for 0-bit Bundle lit field.
            val dontWarnOnZeroDotU = widthValue == 0 && value.num == 0 && value.width.get == 1
            if (!dontWarnOnZeroDotU) {
              val msg = s"Literal value $value is too wide for field ${cloneFields(field)} with width $widthValue"
              Builder.warning(Warning(WarningID.BundleLiteralValueTooWide, msg))
            }
            // Mask the value to the width of the field.
            val mask = (BigInt(1) << widthValue) - 1
            value.cloneWithValue(value.num & mask).cloneWithWidth(width)
          } else if (widthValue > value.width.get) value.cloneWithWidth(width)
          // Otherwise, ensure width is same as that of the field.
          else value

          field -> valuex
      }
    }.toMap
    clone.bind(BundleLitBinding(bundleLitMap))
    clone
  }

  /** The collection of [[chisel3.Data]]
    *
    * This underlying datastructure is a ListMap because the elements must
    * remain ordered for serialization/deserialization. Elements added later
    * are higher order when serialized (this is similar to `Vec`). For example:
    * {{{
    *   // Assume we have some type MyRecord that creates a Record from the ListMap
    *   val record = MyRecord(ListMap("fizz" -> UInt(16.W), "buzz" -> UInt(16.W)))
    *   // "buzz" is higher order because it was added later than "fizz"
    *   record("fizz") := "hdead".U
    *   record("buzz") := "hbeef".U
    *   val uint = record.asUInt
    *   assert(uint === "hbeefdead".U) // This will pass
    * }}}
    */
  override def toString: String = {
    topBindingOpt match {
      case Some(BundleLitBinding(_)) =>
        val contents = _elements.toList.reverse.map { case (name, data) =>
          s"$name=$data"
        }.mkString(", ")
        s"$className($contents)"
      case _ => stringAccessor(s"$className")
    }
  }

  def elements: SeqMap[String, Data]

  // Internal representation of Record elements. _elements makes it
  // possible to check for rebinding issues with Record elements
  // without having to recurse over all elements after the Record is
  // constructed. Laziness of _elements means that this check will
  // occur (only) at the first instance _elements is referenced.
  // Also used to sanitize names and convert to more optimized VectorMap datastructure (if necessary)
  // TODO We should figure out how to combine this with elements
  // The main trick there is storing the sanitized names and making sure every Chisel API uses them
  // Strawman proposal is to put sanitized name in the Slot refs of the children, and then replace
  // _elements and elements with a single Array and deprecate elements as a public API
  // This would also give an opportunity to stop storing elements in reverse order
  private[chisel3] lazy val _elements: VectorMap[String, Data] = {
    // Since elements is a map, it is impossible for two elements to have the same
    // identifier; however, Namespace sanitizes identifiers to make them legal for Firrtl/Verilog
    // which can cause collisions
    // Note that OpaqueTypes cannot have sanitization (the name of the element needs to stay empty)
    //   Use an empty Namespace to indicate OpaqueType
    val namespace = Option.when(!this._isOpaqueType)(Namespace.empty)
    val originalElements = elements
    // Don't create a new map unless necessary, this is much more memory efficient in common case
    // This is true if elements is not a VectorMap or if any names need sanitization
    var needNewMap = !originalElements.isInstanceOf[VectorMap[_, _]]
    val newNames =
      elements.view.map { case (name, field) =>
        if (field.binding.isDefined) {
          throw RebindingException(
            s"Cannot create Record ${this.className}; element ${field} of Record must be a Chisel type, not hardware."
          )
        }
        // namespace.name also sanitizes for firrtl, leave name alone for OpaqueTypes
        val sanitizedName = namespace.map(_.name(name, leadingDigitOk = true)).getOrElse(name)
        if (sanitizedName != name) {
          needNewMap = true
        }
        sanitizedName
      }.toArray // It's very important we eagerly evaluate this sequence.
    if (needNewMap) {
      newNames.view
        .zip(originalElements)
        .map { case (name, (_, data)) => name -> data }
        .to(VectorMap) // VectorMap has O(1) lookup whereas ListMap is O(n)
    } else {
      originalElements.asInstanceOf[VectorMap[String, Data]]
    }
  }

  /** Name for Pretty Printing */
  def className: String = try {
    this.getClass.getSimpleName
  } catch {
    // This happens if your class is defined in an object and is anonymous
    case e: java.lang.InternalError if e.getMessage == "Malformed class name" => this.getClass.toString
  }

  private[chisel3] final def allElements: Seq[Element] = elementsIterator.flatMap(_.allElements).toIndexedSeq

  override def getElements: Seq[Data] = elementsIterator.toIndexedSeq

  final override private[chisel3] def elementsIterator: Iterator[Data] = _elements.iterator.map(_._2)

  // Helper because Bundle elements are reversed before printing
  private[chisel3] def toPrintableHelper(elts: Seq[(String, Data)]): Printable = {
    val xs =
      if (elts.isEmpty) List.empty[Printable] // special case because of dropRight below
      else
        elts.flatMap { case (name, data) =>
          List(PString(s"$name -> "), data.toPrintable, PString(", "))
        }.dropRight(1) // Remove trailing ", "
    PString(s"$className(") + Printables(xs) + PString(")")
  }

  /** Default "pretty-print" implementation
    * Analogous to printing a Map
    * Results in "`\$className(elt0.name -> elt0.value, ...)`"
    */
  def toPrintable: Printable = toPrintableHelper(_elements.toList)

  /** Implementation of cloneType that is [optionally for Record] overridden by the compiler plugin
    *
    * @note This should _never_ be overridden or called in user-code
    */
  protected def _cloneTypeImpl: Record = {
    throwException(
      s"Internal Error! This should have been implemented by the chisel3-plugin. Please file an issue against chisel3"
    )
  }

  override private[chisel3] lazy val _minId: Long = {
    this.elementsIterator.map(_._minId).foldLeft(this._id)(_ min _)
  }
}

/**
  * Mix-in for Bundles that have arbitrary Seqs of Chisel types that aren't
  * involved in hardware construction.
  *
  * Used to avoid raising an error/exception when a Seq is a public member of the
  * bundle.
  * This is useful if we those public Seq fields in the Bundle are unrelated to
  * hardware construction.
  */
trait IgnoreSeqInBundle {
  this: Bundle =>

  override def ignoreSeq: Boolean = true
}

class AutoClonetypeException(message: String) extends chisel3.ChiselException(message)

package experimental {

  class BundleLiteralException(message: String) extends chisel3.ChiselException(message)
  class VecLiteralException(message: String) extends chisel3.ChiselException(message)

}

/** Base class for data types defined as a bundle of other data types.
  *
  * Usage: extend this class (either as an anonymous or named class) and define
  * members variables of [[Data]] subtypes to be elements in the Bundle.
  *
  * Example of an anonymous IO bundle
  * {{{
  *   class MyModule extends Module {
  *     val io = IO(new Bundle {
  *       val in = Input(UInt(64.W))
  *       val out = Output(SInt(128.W))
  *     })
  *   }
  * }}}
  *
  * Or as a named class
  * {{{
  *   class Packet extends Bundle {
  *     val header = UInt(16.W)
  *     val addr   = UInt(16.W)
  *     val data   = UInt(32.W)
  *   }
  *   class MyModule extends Module {
  *     val inPacket = IO(Input(new Packet))
  *     val outPacket = IO(Output(new Packet))
  *     val reg = Reg(new Packet)
  *     reg := inPacket
  *     outPacket := reg
  *   }
  * }}}
  *
  * The fields of a Bundle are stored in an ordered Map called "elements" in reverse order of
  * definition
  * {{{
  *   class MyBundle extends Bundle {
  *     val foo = UInt(8.W)
  *     val bar = UInt(8.W)
  *   }
  *   val wire = Wire(new MyBundle)
  *   wire.elements // VectorMap("bar" -> wire.bar, "foo" -> wire.foo)
  * }}}
  */
abstract class Bundle extends Record {

  private def mustUsePluginMsg: String =
    "The Chisel compiler plugin is now required for compiling Chisel code. " +
      "Please see https://github.com/chipsalliance/chisel3#build-your-own-chisel-projects."
  assert(_usingPlugin, mustUsePluginMsg)

  override def className: String = try {
    this.getClass.getSimpleName match {
      case name if name.startsWith("$anon$") => "AnonymousBundle" // fallback for anonymous Bundle case
      case ""                                => "AnonymousBundle" // ditto, but on other platforms
      case name                              => name
    }
  } catch {
    // This happens if you have nested objects which your class is defined in
    case e: java.lang.InternalError if e.getMessage == "Malformed class name" => this.getClass.toString
  }

  /** The collection of [[Data]]
    *
    * Elements defined earlier in the Bundle are higher order upon
    * serialization. For example:
    * @example
    * {{{
    *   class MyBundle extends Bundle {
    *     val foo = UInt(16.W)
    *     val bar = UInt(16.W)
    *   }
    *   // Note that foo is higher order because its defined earlier in the Bundle
    *   val bundle = Wire(new MyBundle)
    *   bundle.foo := 0x1234.U
    *   bundle.bar := 0x5678.U
    *   val uint = bundle.asUInt
    *   assert(uint === "h12345678".U) // This will pass
    * }}}
    */
  final lazy val elements: SeqMap[String, Data] = _processRawElements(_elementsImpl)

  // The compiler plugin is imperfect at picking out elements statically so we process at runtime
  // checking for errors and filtering out mistakes
  private def _processRawElements(rawElements: Iterable[(String, Any)]): SeqMap[String, Data] = {
    val hardwareFields = rawElements.flatMap {
      case (name, data: Data) =>
        if (data.isSynthesizable) {
          Some(s"$name: $data")
        } else {
          None
        }
      case (name, Some(data: Data)) =>
        if (data.isSynthesizable) {
          Some(s"$name: $data")
        } else {
          None
        }
      case (name, s: scala.collection.Seq[Any]) if s.nonEmpty =>
        s.head match {
          // Ignore empty Seq()
          case d: Data =>
            throwException(
              "Public Seq members cannot be used to define Bundle elements " +
                s"(found public Seq member '${name}'). " +
                "Either use a Vec if all elements are of the same type, or MixedVec if the elements " +
                "are of different types. If this Seq member is not intended to construct RTL, mix in the trait " +
                "IgnoreSeqInBundle."
            )
          case _ => // don't care about non-Data Seq
        }
        None

      case _ => None
    }
    if (hardwareFields.nonEmpty) {
      throw ExpectedChiselTypeException(s"Bundle: $this contains hardware fields: " + hardwareFields.mkString(","))
    }
    VectorMap(rawElements.toSeq.flatMap {
      case (name, data: Data) =>
        Some(name -> data)
      case (name, Some(data: Data)) =>
        Some(name -> data)
      case _ => None
    }.reverse: _*)
  }

  /** This method is implemented by the compiler plugin
    *
    * @note For some reason, the Scala compiler errors on child classes if this method is made
    * virtual. It appears that the way the plugin implements this method is insufficient for
    * implementing virtual methods. It is probably better kept concrete for future refactoring.
    */
  protected def _elementsImpl: Iterable[(String, Any)] = throwException(mustUsePluginMsg)

  /**
    * Overridden by [[IgnoreSeqInBundle]] to allow arbitrary Seqs of Chisel elements.
    */
  def ignoreSeq: Boolean = false

  /** Indicates if a concrete Bundle class was compiled using the compiler plugin
    *
    * Used for optimizing Chisel's performance and testing Chisel itself
    * @note This should not be used in user code!
    */
  protected def _usingPlugin: Boolean = false

  /** Default "pretty-print" implementation
    * Analogous to printing a Map
    * Results in "`Bundle(elt0.name -> elt0.value, ...)`"
    * @note The order is reversed from the order of elements in order to print
    *   the fields in the order they were defined
    */
  override def toPrintable: Printable = toPrintableHelper(_elements.toList.reverse)

}
