# physai-isco-3113 — 電気技術者（ISCO 3113）の電気試験・点検ロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-3113`、ISCO 3113 電気工学技術者）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 電気試験・点検ロボットが電気試験データの記録、点検記録、現場の記録を行う。
その物理的な仕事（狭い配電盤通路での停止、試験プローブの当て込み、ケーブルシースの赤外線点検）を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:switchgear-aisle-stop` | transport | 15 kg の試験器一式を載せて狭い配電盤通路を走り、次の盤の前で止まる | 制動時の最小転倒余裕 | 0.3 以上（estimate） |
| `:place-test-probe` | manipulator | 試験プローブ（リード付き）を持ち上げ、開いた盤内の端子に当てる | 肩関節ピークトルク | 25 N·m（estimate） |
| `:cable-sheath-ir-survey` | thermal | ケーブルラックの PVC シースケーブル。導体が負荷温度にあり、シース表面を赤外線で読む | シース表面温度 | 60 °C（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:test`（`test/eleng/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。
この repo 自身の `.kotoba` test は kbb では走らない（fleet の JVM gate が走らせる）。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **通路停止**: 転倒余裕は制動減速度 0.5 m/s² で 0.82、1.5 m/s² で 0.45、2.0 m/s² で 0.27（限界割れ）、3.0 m/s² で -0.1（転倒）。限界 0.3 を割るのは **1.91 m/s²** から。
   重心 0.90 m・支持半長 0.25 m が効いている。非常停止の減速度をこれ未満に抑えるか、重心を下げる必要がある。
2. **プローブ**: 肩トルクは 0.2 kg で 15.83 N·m、1.5 kg で 22.63 N·m、2.5 kg で 27.98 N·m（限界超過）。限界 25 N·m に達するのは **1.95 kg**。
3. **シース**: 1 時間後の表面温度は導体 40 °C で 38.62 °C、60 °C で 55.86 °C、70 °C で 64.48 °C（限界超過、60 °C 到達は 139.36 s）。
   4 mm のシースでは表面は導体より数 K しか下がらず、限界 60 °C を超えるのは導体 **64.8 °C** から。表面の読みはほぼ導体温度そのものとして扱える。
4. **estimate のままの値**: 転倒余裕 0.3（点検ロボットの安定基準で置き換える）、肩トルク上限 25 N·m（アームの仕様書）、
   シース表面 60 °C（PVC 導体の許容温度に関する規格値と、赤外線点検の判定基準で置き換える）、シースの熱物性（k 0.2、ρ 1400、c 1500）と表面熱伝達率 8 W/m²K。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-3113 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-3113 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
