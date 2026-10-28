package com.evcompanion.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.evcompanion.AppContainer
import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.preset.PidCategory
import kotlinx.coroutines.launch

/**
 * One category's live values (Battery, Cell Voltages, Charging, ...). Subscribes to the query
 * groups that category needs while visible and unsubscribes on destroy (see [PidCategory] and
 * PollingEngine's category-scoped polling) - a cell-voltage screen with 192 rows should only be
 * queried while someone is actually looking at it.
 */
class LiveDataScreen(
    carContext: CarContext,
    private val container: AppContainer,
    private val category: PidCategory,
) : Screen(carContext) {

    private var values: Map<String, DecodedValue> = emptyMap()

    init {
        container.pollingEngine.subscribe(category)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                container.pollingEngine.unsubscribe(category)
            }
        })
        lifecycleScope.launch {
            container.pollingEngine.values.collect {
                values = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val categoryValues = values.values.filter { it.category == category }.sortedBy { it.displayName }

        if (categoryValues.isEmpty()) {
            listBuilder.setNoItemsMessage("Waiting for data...")
        }
        categoryValues.forEach { v ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(v.displayName)
                    .addText("%.2f %s".format(v.value, v.unit))
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(category.label).setStartHeaderAction(Action.BACK).build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
