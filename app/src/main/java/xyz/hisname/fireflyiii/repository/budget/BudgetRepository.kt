/*
 * Copyright (c)  2018 - 2021 Daniel Quah
 * Copyright (c)  2021 ASDF Dev Pte. Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.hisname.fireflyiii.repository.budget

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import xyz.hisname.fireflyiii.data.local.dao.*
import xyz.hisname.fireflyiii.data.remote.firefly.api.BudgetService
import xyz.hisname.fireflyiii.repository.models.budget.BudgetData
import xyz.hisname.fireflyiii.repository.models.budget.budgetList.BudgetListData
import xyz.hisname.fireflyiii.util.network.retrofitCallback
import java.math.BigDecimal

@Suppress("RedundantSuspendModifier")
@WorkerThread
class BudgetRepository(private val budget: BudgetDataDao,
                       private val budgetList: BudgetListDataDao,
                       private val spentDao: SpentDataDao,
                       private val budgetLimitDao: BudgetLimitDao,
                       private val budgetService: BudgetService) {

    suspend fun insertBudget(budgetData: BudgetData){
        budget.insert(budgetData)
    }

    suspend fun deleteAllBudget() = budget.deleteAllBudget()

    suspend fun insertBudgetList(budgetData: BudgetListData){
        budgetList.insert(budgetData)
        val spentList = budgetData.budgetListAttributes.spent
        if(spentList.isNotEmpty()){
            spentList.forEach { spent ->
                spent.spentId = budgetData.budgetListId
                spentDao.insert(spent)
            }
        }
    }

    suspend fun allActiveSpentList(currencyCode: String, startDate: String, endDate: String): BigDecimal{
        try {
            val budgetListData: MutableList<BudgetListData> = arrayListOf()
            val networkCall = budgetService.getPaginatedSpentBudget(1, startDate, endDate)
            val responseBody = networkCall.body()
            if (responseBody != null && networkCall.isSuccessful) {
                budgetListData.addAll(responseBody.data)
                if (responseBody.meta.pagination.current_page != responseBody.meta.pagination.total_pages) {
                    for (pagination in 2..responseBody.meta.pagination.total_pages) {
                        val repeatedCall = budgetService.getPaginatedSpentBudget(pagination, startDate, endDate)
                        val repeatedCallBody = repeatedCall.body()
                        if (repeatedCallBody != null) {
                            budgetListData.addAll(repeatedCallBody.data)
                        }
                    }
                }
                deleteBudgetList()
                budgetListData.forEach { budgetList ->
                    insertBudgetList(budgetList)
                }
            }
        } catch (exception: Exception){ }
        return spentDao.getAllActiveBudgetList(currencyCode)
    }

    suspend fun deleteBudgetList() = budgetList.deleteAllBudgetList()

    suspend fun getConstraintBudgetWithCurrency(startDate: String, endDate: String,
                                                currencyCode: String) =
            budget.getConstraintBudgetWithCurrency(startDate, endDate, currencyCode)

    suspend fun getBudgetByCurrencyAndStartEndDate(startDate: String, endDate: String,
                                                   currencyCode: String) =
            budget.getBudgetByCurrencyAndStartEndDate(startDate, endDate, currencyCode)

    suspend fun getAllAvailableBudget(startDate: String, endDate: String,
                                      currencyCode: String): BigDecimal {
        try {
            val networkCall = budgetService.getAvailableBudget(1, startDate, endDate)
            val responseBody = networkCall.body()
            val availableBudget: MutableList<BudgetData> = arrayListOf()
            if (responseBody != null && networkCall.isSuccessful) {
                availableBudget.addAll(responseBody.budgetData)
                if (responseBody.meta.pagination.current_page != responseBody.meta.pagination.total_pages) {
                    for (pagination in 2..responseBody.meta.pagination.total_pages) {
                        val repeatedCall = budgetService.getAvailableBudget(pagination, startDate, endDate)
                        val repeatedCallBody = repeatedCall.body()
                        if (repeatedCallBody != null) {
                            availableBudget.addAll(repeatedCallBody.budgetData)
                        }
                    }
                }
                deleteAllBudget()
                availableBudget.forEach { budget ->
                    insertBudget(budget)
                }
            }
        } catch (exception: Exception){ }
        return budget.getConstraintBudgetWithCurrency(startDate, endDate, currencyCode)
    }

    suspend fun updateBudget(budgetId: Long, currencyCode: String, amount: String,
                             startDate: String, endDate: String): BudgetData{
        val networkCall = budgetService.updateAvailableBudget(budgetId, currencyCode, amount, startDate, endDate)
        val responseBody = networkCall.body()
        if (responseBody != null && networkCall.isSuccessful) {
            insertBudget(responseBody.data)
            return responseBody.data
        } else {
            throw Exception("There was an issue updating your budget")
        }
    }

    suspend fun getBudgetLimitByName(budgetName: String, startDate: String, endDate: String, currencyCode: String): BigDecimal{
        val budgetNameList = budgetList.searchBudgetName(budgetName)
        val budgetId = budgetNameList[0].budgetListId
        try {
            val networkCall = budgetService.getBudgetLimit(budgetId, startDate, endDate)
            val responseBody = networkCall.body()
            // There is no pagination in API
            if (responseBody != null && networkCall.isSuccessful) {
                budgetLimitDao.deleteAllBudgetLimit()
                responseBody.budgetLimitData.forEach { limitData ->
                    budgetLimitDao.insert(limitData)
                }
            }
        } catch (exception: Exception){ }
        return budgetLimitDao.getBudgetLimitByIdAndCurrencyCodeAndDate(budgetId, currencyCode, startDate, endDate)
    }

    suspend fun getAllBudgetName(): Flow<List<String>> {
        try {
            val budgetListData: MutableList<BudgetListData> = arrayListOf()
            val networkCall = budgetService.getPaginatedSpentBudget(1)
            val responseBody = networkCall.body()
            if(responseBody != null && networkCall.isSuccessful){
                deleteBudgetList()
                budgetListData.addAll(responseBody.data)
                if (responseBody.meta.pagination.current_page != responseBody.meta.pagination.total_pages) {
                    for(pagination in 2..responseBody.meta.pagination.total_pages){
                        val networkBody = budgetService.getPaginatedSpentBudget(pagination).body()
                        if(networkBody != null){
                            budgetListData.addAll(networkBody.data)
                        }
                    }
                }
            }
            budgetListData.forEach { data ->
                insertBudgetList(data)
            }
        } catch (exception: Exception){  }
        return budgetList.getAllBudgetName()
    }

    suspend fun getAllBudget(){
        try {
            val availableBudget: MutableList<BudgetData> = arrayListOf()
            val networkCall = budgetService.getAllBudget()
            val responseBody = networkCall.body()
            if(responseBody != null && networkCall.isSuccessful){
                deleteAllBudget()
                val networkData = networkCall.body()
                if (networkData?.meta?.pagination?.current_page != networkData?.meta?.pagination?.total_pages) {
                    networkData?.meta?.pagination?.let { page ->
                        for (pagination in 2..page.total_pages){
                            budgetService.getPaginatedBudget(pagination).enqueue(retrofitCallback({ response ->
                                response.body()?.budgetData?.forEach { budgetList ->
                                    availableBudget.add(budgetList)
                                }
                            }))
                        }
                    }
                }
                networkData?.budgetData?.forEach { budgetData ->
                    insertBudget(budgetData)
                }
                availableBudget.forEach { budgetData ->
                    insertBudget(budgetData)
                }
            }

        } catch (exception: Exception){ }
    }
}