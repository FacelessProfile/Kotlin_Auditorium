# Отчёт о проделанной работе
**Период:** 12 неделя второго сезона проекта  
**Дата составления:** 22.05.2026  

## Описание проделанной работы

За двенадцатую неделю работы над проектом в этом сезоне были выполнены следующие шаги:
* Дополнено и в большинстве мест переработано UI для лучшего UX и соответствия цветовой схеме
  * Добавлено боковое меню для навигации между фрагментами
  * Мок фрагменты для профиля, успеваемости и настроек были добавлены
  
## Поэтапное описание

### UI Changes
Добавлено боковое меню
```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <group android:checkableBehavior="single">
        <item
            android:id="@+id/userHomeFragment"
            android:icon="@drawable/ic_home"
            android:title="Главная" />
        <item
            android:id="@+id/profileFragment"
            android:icon="@drawable/ic_person"
            android:title="Профиль" />
        <item
            android:id="@+id/historyFragment"
            android:icon="@drawable/ic_history"
            android:title="История посещений" />
        <item
            android:id="@+id/settingsFragment"
            android:icon="@drawable/ic_settings"
            android:title="Настройки" />
    </group>
    <item android:title="Аккаунт">
        <menu>
            <item
                android:id="@+id/logout"
                android:icon="@drawable/ic_logout"
                android:title="Выйти" />
        </menu>
    </item>
</menu>
```
<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/d086ba80-a7d6-4f94-a9af-5cc510975305" />

Переработан сам внешний вид интерфейса под определенный цвет (пока на согласовании) + добавлены иконки для лучшего UX
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/rootLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/windowBackground">

    <TextView
        android:id="@+id/tvWelcome"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        android:text="Добро пожаловать,\nЗагрузка..."
        android:textAlignment="center"
        android:textColor="?android:attr/textColorPrimary"
        android:textSize="24sp"
        android:textStyle="bold"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/nfcCard"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="24dp"
        android:layout_marginTop="32dp"
        android:layout_marginEnd="24dp"
        app:cardCornerRadius="16dp"
        app:cardElevation="0dp"
        app:strokeWidth="1dp"
        app:strokeColor="#33888888"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/tvWelcome">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:orientation="vertical"
            android:padding="24dp">

            <ImageView
                android:id="@+id/statusIcon"
                android:layout_width="120dp"
                android:layout_height="120dp"
                android:src="@drawable/ic_nfc"
                app:tint="?attr/colorPrimary" />

            <TextView
                android:id="@+id/statusText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:text="NFC активен"
                android:textAlignment="center"
                android:textColor="?android:attr/textColorSecondary"
                android:textSize="16sp" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnScan"
        android:layout_width="0dp"
        android:layout_height="60dp"
        android:layout_marginStart="24dp"
        android:layout_marginTop="24dp"
        android:layout_marginEnd="24dp"
        android:text="Сканировать QR"
        android:textAllCaps="false"
        android:textSize="18sp"
        app:cornerRadius="12dp"
        app:icon="@drawable/ic_qr_code"
        app:iconGravity="textStart"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/nfcCard" />

    <TextView
        android:id="@+id/tvInfo"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="32dp"
        android:text="Режим эмуляции карты активен"
        android:textColor="?android:attr/textColorSecondary"
        android:textSize="12sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```
<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/8c418073-9f71-47f3-a47d-54573e63ad2f" />


## TODO
- [ ] Обговорить с Ромой нужные ручки для взаимодействия с приложением
- [ ] Согласовать дальнейшие изменения с вадимом и Викой
