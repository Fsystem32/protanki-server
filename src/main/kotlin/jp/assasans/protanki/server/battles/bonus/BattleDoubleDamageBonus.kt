package jp.assasans.protanki.server.battles.bonus

import kotlin.time.Duration.Companion.seconds
import jp.assasans.protanki.server.BonusType
import jp.assasans.protanki.server.battles.Battle
import jp.assasans.protanki.server.battles.BattleTank
import jp.assasans.protanki.server.battles.effect.DoubleDamageEffect
import jp.assasans.protanki.server.math.Quaternion
import jp.assasans.protanki.server.math.Vector3

class BattleDoubleDamageBonus(battle: Battle, id: Int, position: Vector3, rotation: Quaternion) :
  BattleBonus(battle, id, position, rotation, 20.seconds) {
  override val type: BonusType = BonusType.DoubleDamage

  override suspend fun activate(tank: BattleTank) {
    val effect = DoubleDamageEffect(tank)
    tank.effects.add(effect)
    effect.run()
  }
}
