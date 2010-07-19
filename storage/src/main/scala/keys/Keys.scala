package edu.berkeley.cs.scads.keys

import java.text.ParsePosition

abstract class Key extends Ordered[Key] {
	override def compare(other: Key) = {
		other match {
			case MaxKey => -1
			case MinKey => 1
			case k:Key => serialize.compare(k.serialize)
		}
	}

	def toString : String
	def serialize: String
}

@deprecated
object StringKey {
	def deserialize(input: String, pos: ParsePosition): StringKey = {
		new StringKey(deserialize_toString(input,pos))
	}
	def deserialize_toString(input: String, pos: ParsePosition): String = {
		assert(input.charAt(pos.getIndex) == '\'',"Not serialized key: "+input)
		pos.setIndex(pos.getIndex + 1)

		val sb = new StringBuilder()
		while(input.charAt(pos.getIndex) != '\'' && pos.getIndex < input.length){
			sb.append(input.charAt(pos.getIndex))
			pos.setIndex(pos.getIndex + 1)
		}
		pos.setIndex(pos.getIndex + 1)
		sb.toString()
	}
}

@deprecated
@serializable
case class StringKey(stringVal: String) extends Key {
	override def toString:String = "StringKey(" + stringVal + ")"
	//TODO: handle "s correctly
	def serialize:String = "'" + stringVal + "'"
}

@deprecated
object NumericKey {
	val maxKey = 9999999999999999L
	val keyFormat = new java.text.DecimalFormat("0000000000000000")

	def apply(i: Int) = new NumericKey(i)

	def deserialize(input: String): NumericKey[Long] = deserialize(input, new ParsePosition(0))
	def deserialize(input: String, pos: ParsePosition): NumericKey[Long] = {
		val num = keyFormat.parse(input, pos).longValue()
		if(num < 0)
			new NumericKey[Long]((maxKey - Math.abs(num)) * -1)
		else
			new NumericKey[Long](num)
	}
}

@deprecated
@serializable
case class NumericKey[numType](numericVal: Long) extends Key {
	override def toString:String = "NumericKey(" + numericVal + ")"
	def serialize:String =
		if(numericVal >= 0)
			NumericKey.keyFormat.format(numericVal)
		else
			"-" + NumericKey.keyFormat.format(NumericKey.maxKey - Math.abs(numericVal))
}

class InvalidKey extends Exception

@serializable
object MinKey extends Key {
	override def compare(other: Key) = if (other==MinKey) {0} else {-1}
	override def toString : String  = "MinKey"
	def serialize: String = throw new InvalidKey
}

@serializable
object MaxKey extends Key {
	override def compare(other: Key) = if (other==MaxKey) {0} else {1}
	override def toString : String  = "MaxKey"
	def serialize: String = throw new InvalidKey
}

trait AutoKey {
	implicit def stringToKey(s:String) = new StringKey(s)
	implicit def intToKey(i:Int) = new NumericKey[Int](i)
	implicit def longToKey(i:Long) = new NumericKey[Long](i)
}

class TransparentKey(value: String) extends Key {
	def serialize: String = value
	override def toString = value
}