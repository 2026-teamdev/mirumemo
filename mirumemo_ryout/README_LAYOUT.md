# みるメモ v3 レイアウト作成

## 開き方

zipを展開し、`settings.gradle.kts` のある `mirumemo` フォルダをAndroid Studioで開いてください。namespaceは `com.example.logiks.mirumemo` です。

## 作成内容

本文の全22画面を、18組のActivity/XMLと4組のDialogFragment/XMLで作成しました。画面ごとの対応は `SCREEN_LIST.md` を参照してください。

共通トップバー2種類、作品・ユーザ・感想・ジャンルの行レイアウト、会員・管理者用メニュー、色セレクタ、仮ロゴと各アイコンを作成しました。仮ロゴは `app/src/main/res/drawable/logo_mirumemo.xml` を同名リソースに差し替えられます。別形式の画像へ変更する場合は、同名のXMLを削除してリソースが重複しないようにしてください。

ロジックは未実装です。ボタン、リンク、タブの切替、ダイアログの表示、画像選択は動きません。RecyclerViewはAdapterを持たないため、実行時は空欄です。Android Studioのレイアウトプレビューでは tools:listitem / tools:itemCount により行の見た目を確認できます。ドロップダウンには選択肢を設定していません。履歴の初期タブは、TabLayoutがXMLの先頭タブを選択する標準挙動で「いいね」になります。

## 検証結果

- XML全52ファイルの構文とリソース参照を確認しました（既存のテーマ・ランチャー用リソースを含む）。
- 18 ActivityのManifest登録、MainActivityのみexported=true、LAUNCHER維持を確認しました。
- 22画面のKotlin/XML対応、ボトムナビの選択ID、管理者画面のトップバーを確認しました。
- クリック処理、Intent、DB、通信、Adapter等がないことを確認しました。
- MainActivity.kt、Gradle設定一式とテーマは元ファイルと同一です。activity_main.xmlとManifestは指示に従って変更しました。
- ビルドは未完了です。環境にGradle 9.3.1の配布物がなく、取得先へのネットワーク接続ができないため、Gradleの起動段階で停止しました。Android端末・エミュレータでの表示確認、AndroidリソースのリンクとKotlinコンパイルは未確認です。Android StudioでのSync・ビルドが必要です。

## 前提一覧（本文を優先した箇所を含む）

1. 元zipは二重のフォルダ構成でした。`mirumemo/mirumemo` のGradleプロジェクトを採用し、外側の断片的なappフォルダは統合せず、外側のactivity_admin_works.xmlを書式見本として参照しました。納品は一重のmirumemoフォルダに整理しました。
2. 添付の画面遷移図・概念クラス図PNGは実際に開けたため、参照しました。PDFも本文抽出で会員情報等を確認しました。astaは編集・変更していません。
3. 画面遷移図はログイン入力をIDと表記していますが、本文の「メールアドレス」に従いました。
4. 画面遷移図のユーザ情報管理画面には「編集なし」の注記がありますが、本文に従って編集欄と編集・削除ボタンを配置しました。
5. 図やPDFの視聴話数、違反報告、BAN、検索・管理履歴等は、本文の各画面の項目一覧にないため追加していません。プロフィール編集は本文どおりユーザ名・メールアドレス・生年月日のみで、性別欄を追加していません。
6. 「全22画面」は本文の番号に従い、DialogFragmentの4画面を含みます。LoginActivity、会員登録完了専用画面、ジャンル追加専用画面は作成していません。
7. 性別・生年月日は入力枠だけとし、選択肢、日付ピッカー、入力判定は追加していません。確認・プロフィール・詳細の表示文はレイアウト用の仮表示です。
8. 集計は枠のみとし、数値、表、グラフ等は追加していません。感想編集には一覧内の編集・削除ボタンを置き、別の編集画面は追加していません。
9. パスワード再設定のメールURL入口はコメントのみです。Manifestにディープリンクや公開Activityを設定すると「MainActivity以外exported=false」に反するため設定していません。
10. RecyclerView・DialogFragmentは既存のMaterial / AppCompatの推移依存で供給される構成を引き継ぎ、build.gradle.ktsとlibs.versions.tomlを変更していません。
11. ステータスバー対策は本文指定のfitsSystemWindows=trueのみです。新たなInsets処理はロジック禁止のため追加していません。targetSdk 36の実端末での表示は後工程で確認してください。
12. 作品登録の登録完了後はAdminWorksActivityに戻る想定をコメントに記載しました。視聴記録登録・ユーザ編集削除など、本文に完了後の遷移先が指定されていない箇所は処理を後工程としています。

13. 夜間専用の旧colors.xmlは破棄し、DayNightテーマ自体は維持しながら、本文指定の共通色を昼夜とも参照するようにしました。

## 作成・変更したファイル（プロジェクトルートからのパス）

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/logiks/mirumemo/AdminReviewDialogFragment.kt`
- `app/src/main/java/com/example/logiks/mirumemo/AdminUserManageActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/AdminWorkDetailActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/AdminWorkEditDialogFragment.kt`
- `app/src/main/java/com/example/logiks/mirumemo/AdminWorkRegisterActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/HistoryActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/ProfileActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/ProfileEditDialogFragment.kt`
- `app/src/main/java/com/example/logiks/mirumemo/WorkDetailDialogFragment.kt`
- `app/src/main/res/color/bottom_nav_item_color.xml`
- `app/src/main/res/drawable/bg_stats_area.xml`
- `app/src/main/res/drawable/ic_image_placeholder.xml`
- `app/src/main/res/drawable/ic_nav_genres.xml`
- `app/src/main/res/drawable/ic_nav_history.xml`
- `app/src/main/res/drawable/ic_nav_mypage.xml`
- `app/src/main/res/drawable/ic_nav_profile.xml`
- `app/src/main/res/drawable/ic_nav_record.xml`
- `app/src/main/res/drawable/ic_nav_stats.xml`
- `app/src/main/res/drawable/ic_nav_users.xml`
- `app/src/main/res/drawable/ic_nav_works.xml`
- `app/src/main/res/drawable/logo_mirumemo.xml`
- `app/src/main/res/layout/activity_admin_genres.xml`
- `app/src/main/res/layout/activity_admin_stats.xml`
- `app/src/main/res/layout/activity_admin_user_manage.xml`
- `app/src/main/res/layout/activity_admin_users.xml`
- `app/src/main/res/layout/activity_admin_work_detail.xml`
- `app/src/main/res/layout/activity_admin_work_register.xml`
- `app/src/main/res/layout/activity_admin_works.xml`
- `app/src/main/res/layout/activity_history.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_mypage.xml`
- `app/src/main/res/layout/activity_password_email.xml`
- `app/src/main/res/layout/activity_password_email_sent.xml`
- `app/src/main/res/layout/activity_password_reset.xml`
- `app/src/main/res/layout/activity_password_reset_complete.xml`
- `app/src/main/res/layout/activity_profile.xml`
- `app/src/main/res/layout/activity_record.xml`
- `app/src/main/res/layout/activity_register.xml`
- `app/src/main/res/layout/activity_register_confirm.xml`
- `app/src/main/res/layout/dialog_admin_review.xml`
- `app/src/main/res/layout/dialog_admin_work_edit.xml`
- `app/src/main/res/layout/dialog_profile_edit.xml`
- `app/src/main/res/layout/dialog_work_detail.xml`
- `app/src/main/res/layout/item_genre.xml`
- `app/src/main/res/layout/item_review.xml`
- `app/src/main/res/layout/item_user.xml`
- `app/src/main/res/layout/item_work.xml`
- `app/src/main/res/layout/layout_top_bar_admin.xml`
- `app/src/main/res/layout/layout_top_bar_member.xml`
- `app/src/main/res/menu/menu_admin_bottom_nav.xml`
- `app/src/main/res/menu/menu_member_bottom_nav.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/strings.xml`

## 破棄した既存ファイル

- `app/src/main/res/values-night/colors.xml`（指定色の夜間上書きを防止）
- `app/src/main/java/com/example/logiks/mirumemo/LogActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/MemberHomeActivity.kt`
- `app/src/main/java/com/example/logiks/mirumemo/RegisterCompleteActivity.kt`
- `app/src/main/res/drawable/ic_nav_home.xml`
- `app/src/main/res/drawable/ic_nav_log.xml`
- `app/src/main/res/layout/activity_log.xml`
- `app/src/main/res/layout/activity_member_home.xml`
- `app/src/main/res/layout/activity_register_complete.xml`

## その他の同梱資料

- `SCREEN_LIST.md`：22画面のファイルペア一覧
- `README_LAYOUT.md`：この説明書と前提・検証結果・変更一覧

元zipの.gradle、.idea、.kotlin、build、local.properties等は含めていません。
