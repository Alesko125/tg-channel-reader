package ua.tgreader

data class CatalogChannel(val name: String, val title: String, val note: String)
data class CatalogSection(val title: String, val channels: List<CatalogChannel>)

/**
 * Public channels offered in "Каталог каналів". All of them were checked to have a public
 * web preview (t.me/s/…) and to be active; the app reads only what that preview shows.
 */
object Catalog {
    val sections = listOf(
        CatalogSection(
            "Моніторинг загроз (дрони, ракети)",
            listOf(
                CatalogChannel("Northern_Sich_ukr", "Північний Сич 🦉", "повітряні загрози, аналітика"),
                CatalogChannel("war_monitor", "monitor", "рух дронів і ракет"),
                CatalogChannel("monitorwarr", "monitoring", "моніторинг повітряних загроз"),
                CatalogChannel("eRadarrua", "єРадар", "повітряна тривога, ракетна небезпека"),
                CatalogChannel("raketa_trevoga", "Чому тривога | Радар", "пояснює, чому оголошено тривогу"),
                CatalogChannel("povitryanatrivogaaa", "Карта повітряних тривог", "тривоги, вибухи, ракети"),
                CatalogChannel("monitor_ukr", "Моніторинг | Україна", "загрози по областях"),
                CatalogChannel("PpoUARadar", "ППО РАДАР", "карта тривог, моніторинг"),
                CatalogChannel("strategicontrol", "Strategic Control", "стратегічна авіація, пуски"),
                CatalogChannel("monitoring_ukrainian", "Monitoring Ukrainian", "моніторинг загроз"),
                CatalogChannel("vanek_nikolaev", "Николаевский Ванёк", "Миколаїв і південь"),
                CatalogChannel("air_alert_ua", "Повітряна Тривога", "початок і відбій тривог"),
            ),
        ),
        CatalogSection(
            "Офіційні",
            listOf(
                CatalogChannel("kpszsu", "Повітряні Сили ЗСУ", "офіційно про повітряні атаки"),
                CatalogChannel("GeneralStaffZSU", "Генеральний штаб ЗСУ", "зведення Генштабу"),
                CatalogChannel("ministry_of_defense_ua", "Міністерство оборони", ""),
                CatalogChannel("V_Zelenskiy_official", "Зеленський / Official", ""),
                CatalogChannel("SBUkr", "Служба безпеки України", ""),
                CatalogChannel("dsns_telegram", "ДСНС України", "надзвичайні ситуації"),
                CatalogChannel("VA_Kyiv", "КМВА", "Київська міська військова адміністрація"),
                CatalogChannel("CenterCounteringDisinformation", "Центр протидії дезінформації", ""),
                CatalogChannel("spravdi", "SPRAVDI", "спростування фейків"),
            ),
        ),
        CatalogSection(
            "Новини",
            listOf(
                CatalogChannel("ukrpravda_news", "Українська правда", ""),
                CatalogChannel("suspilnenews", "Суспільне Новини", ""),
                CatalogChannel("hromadske_ua", "hromadske", ""),
                CatalogChannel("uniannet", "УНІАН", ""),
                CatalogChannel("ukrinform_news", "Укрінформ", ""),
                CatalogChannel("UkraineNow", "Ukraine NOW", ""),
                CatalogChannel("bbcukrainian", "BBC News Україна", ""),
                CatalogChannel("liganet", "LIGA.net", ""),
                CatalogChannel("nvua_official", "NV", ""),
                CatalogChannel("babel", "Бабель", ""),
                CatalogChannel("epravda", "Економічна правда", "економіка"),
                CatalogChannel("insiderUKR", "INSIDER UA", ""),
                CatalogChannel("truexanewsua", "Труха⚡️Україна", ""),
                CatalogChannel("lachentyt", "Лачен пише", ""),
                CatalogChannel("operativnoZSU", "Оперативний ЗСУ", "фронт"),
                CatalogChannel("Tsaplienko", "Цаплієнко", "фронт"),
                CatalogChannel("ssternenko", "Стерненко", ""),
            ),
        ),
        CatalogSection(
            "Технології",
            listOf(
                CatalogChannel("itcua", "ITC.UA", "IT-новини і технології"),
            ),
        ),
    )
}
