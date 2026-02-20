package com.driver.profitcalculator

import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

/**
 * Smart Classifier for fare extraction
 * Handles zone-based separation and double-ping scenarios
 */
class FareAnalyzer {

    companion object {
        const val TAG = "FareAnalyzer"
        
        // Cost calculation constants (Delhi market)
        const val COST_PER_KM = 2.40
        const val PROFIT_THRESHOLD = 6.0
        
        // Zone definitions (normalized 0-1 coordinates for 720x1280 capture)
        // Rapido white cards typically appear in upper-middle screen
        val RAPIDO_ZONE = Zone(0.0f, 0.15f, 1.0f, 0.55f)
        
        // Uber dark cards appear in lower-middle screen
        val UBER_ZONE = Zone(0.0f, 0.40f, 1.0f, 0.75f)
        
        // Overlap zone where both might appear (double-ping scenario)
        val OVERLAP_ZONE = Zone(0.0f, 0.40f, 1.0f, 0.55f)
    }

    data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float)
    
    data class DetectedText(
        val text: String,
        val boundingBox: Rect,
        val confidence: Float
    )
    
    data class FareResult(
        val platform: Platform,
        val baseFare: Double,
        val bonus: Double,
        val pickupDistance: Double,
        val dropDistance: Double,
        val isBlocked: Boolean = false,
        val confidence: Float = 0f
    ) {
        val totalFare: Double get() = baseFare + bonus
        val totalDistance: Double get() = pickupDistance + dropDistance
        val cost: Double get() = totalDistance * COST_PER_KM
        val netProfit: Double get() = totalFare - cost
        val profitPerKm: Double get() = if (totalDistance > 0) netProfit / totalDistance else 0.0
        val isProfitable: Boolean get() = profitPerKm >= PROFIT_THRESHOLD
    }
    
    enum class Platform { RAPIDO, UBER, UNKNOWN }

    // Regex patterns - compiled once for performance
    private val currencyPattern = Pattern.compile("[₹Rs.]\\s*(\\d+)")
    private val rapidoBonusPattern = Pattern.compile(
        "Customer\\s+added\\s+[₹Rs.]*\\s*(\\d+\\.?\\d*)", 
        Pattern.CASE_INSENSITIVE
    )
    private val rapidoDistancePattern = Pattern.compile(
        "(\\d+)\\s*(?:Km|KM|km)", 
        Pattern.CASE_INSENSITIVE
    )
    private val uberPremiumPattern = Pattern.compile(
        "\\+\\s*[₹Rs.]*\\s*(\\d+\\.?\\d*)\\s*(?:Premium|Surge)", 
        Pattern.CASE_INSENSITIVE
    )
    private val uberDistancePattern = Pattern.compile(
        "\\((\\d+\\.?\\d*)\\s*(?:km|KM|Km)\\)"
    )
    private val blockedPattern = Pattern.compile(
        "(?:blocked|hidden|price\\s*not\\s*shown)", 
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Main analysis entry point
     * Separates text blocks by zone and extracts fare data
     */
    fun analyze(textBlocks: List<Text.TextBlock>, imageWidth: Int, imageHeight: Int): AnalysisResult {
        val allLines = mutableListOf<DetectedText>()
        
        // Flatten all lines with bounding boxes
        textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.boundingBox?.let { box ->
                    allLines.add(DetectedText(
                        text = line.text,
                        boundingBox = box,
                        confidence = line.confidence
                    ))
                }
            }
        }
        
        // Separate by zones
        val rapidoLines = allLines.filter { isInZone(it.boundingBox, RAPIDO_ZONE, imageWidth, imageHeight) }
        val uberLines = allLines.filter { isInZone(it.boundingBox, UBER_ZONE, imageWidth, imageHeight) }
        
        // Check for overlap (double-ping scenario)
        val overlapLines = allLines.filter { isInZone(it.boundingBox, OVERLAP_ZONE, imageWidth, imageHeight) }
        val isDoublePing = overlapLines.size > 5 // Heuristic: many lines in overlap suggests both apps
        
        Log.d(TAG, "Detected ${rapidoLines.size} Rapido lines, ${uberLines.size} Uber lines")
        Log.d(TAG, "Double-ping detected: $isDoublePing")
        
        // Extract fares
        val rapidoFare = extractRapidoFare(rapidoLines)
        val uberFare = extractUberFare(uberLines)
        
        return AnalysisResult(
            rapido = rapidoFare,
            uber = uberFare,
            isDoublePing = isDoublePing,
            allDetectedText = allLines.map { it.text }
        )
    }

    /**
     * Extract Rapido fare from white card zone
     */
    private fun extractRapidoFare(lines: List<DetectedText>): FareResult? {
        var baseFare = 0.0
        var bonus = 0.0
        var pickupDist = 0.0
        var dropDist = 0.0
        var foundBaseFare = false
        var foundPickup = false
        
        // Sort by Y position for logical reading
        val sortedLines = lines.sortedBy { it.boundingBox.centerY() }
        
        sortedLines.forEach { line ->
            val text = line.text
            
            // Base fare - usually prominent ₹XX
            if (!foundBaseFare) {
                currencyPattern.matcher(text).let { matcher ->
                    if (matcher.find()) {
                        baseFare = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                        foundBaseFare = true
                        Log.d(TAG, "Rapido base: ₹$baseFare")
                    }
                }
            }
            
            // Bonus: "Customer added ₹X.X extra"
            rapidoBonusPattern.matcher(text).let { matcher ->
                if (matcher.find()) {
                    bonus = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                    Log.d(TAG, "Rapido bonus: ₹$bonus")
                }
            }
            
            // Distance: "X Km" - first occurrence is usually pickup
            rapidoDistancePattern.matcher(text).let { matcher ->
                if (matcher.find()) {
                    val dist = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                    if (!foundPickup) {
                        pickupDist = dist
                        foundPickup = true
                    } else {
                        dropDist = dist
                    }
                    Log.d(TAG, "Rapido distance: $dist km")
                }
            }
        }
        
        return if (foundBaseFare && (pickupDist > 0 || dropDist > 0)) {
            FareResult(
                platform = Platform.RAPIDO,
                baseFare = baseFare,
                bonus = bonus,
                pickupDistance = pickupDist,
                dropDistance = dropDist,
                confidence = calculateConfidence(lines.size, foundBaseFare, pickupDist > 0)
            )
        } else null
    }

    /**
     * Extract Uber fare from dark card zone
     */
    private fun extractUberFare(lines: List<DetectedText>): FareResult? {
        var baseFare = 0.0
        var bonus = 0.0
        var pickupDist = 0.0
        var dropDist = 0.0
        var foundBaseFare = false
        var foundPickup = false
        var isBlocked = false
        
        // Check for blocked price indicator
        lines.forEach { line ->
            if (blockedPattern.matcher(line.text).find()) {
                isBlocked = true
                Log.d(TAG, "Uber price blocked detected")
            }
        }
        
        // Sort by Y position
        val sortedLines = lines.sortedBy { it.boundingBox.centerY() }
        
        sortedLines.forEach { line ->
            val text = line.text
            
            // Base fare
            if (!foundBaseFare) {
                currencyPattern.matcher(text).let { matcher ->
                    if (matcher.find()) {
                        baseFare = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                        foundBaseFare = true
                        Log.d(TAG, "Uber base: ₹$baseFare")
                    }
                }
            }
            
            // Premium/Surge: "+₹X.XX Premium"
            uberPremiumPattern.matcher(text).let { matcher ->
                if (matcher.find()) {
                    bonus = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                    Log.d(TAG, "Uber premium: ₹$bonus")
                }
            }
            
            // Distance: "(X.X km)"
            uberDistancePattern.matcher(text).let { matcher ->
                if (matcher.find()) {
                    val dist = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                    if (!foundPickup) {
                        pickupDist = dist
                        foundPickup = true
                    } else {
                        dropDist = dist
                    }
                    Log.d(TAG, "Uber distance: $dist km")
                }
            }
        }
        
        // Fail-safe: If we have distance but no base fare, price is blocked
        if (!foundBaseFare && (pickupDist > 0 || dropDist > 0)) {
            isBlocked = true
        }
        
        return if (isBlocked) {
            FareResult(
                platform = Platform.UBER,
                baseFare = 0.0,
                bonus = 0.0,
                pickupDistance = 0.0,
                dropDistance = 0.0,
                isBlocked = true,
                confidence = 1.0f
            )
        } else if (foundBaseFare && (pickupDist > 0 || dropDist > 0)) {
            FareResult(
                platform = Platform.UBER,
                baseFare = baseFare,
                bonus = bonus,
                pickupDistance = pickupDist,
                dropDistance = dropDist,
                confidence = calculateConfidence(lines.size, foundBaseFare, pickupDist > 0)
            )
        } else null
    }

    private fun calculateConfidence(lineCount: Int, hasBase: Boolean, hasDist: Boolean): Float {
        var confidence = 0f
        if (lineCount > 3) confidence += 0.3f
        if (hasBase) confidence += 0.4f
        if (hasDist) confidence += 0.3f
        return confidence
    }

    private fun isInZone(box: Rect, zone: Zone, imageWidth: Int, imageHeight: Int): Boolean {
        val centerX = box.centerX().toFloat() / imageWidth
        val centerY = box.centerY().toFloat() / imageHeight
        return centerX >= zone.left && centerX <= zone.right &&
               centerY >= zone.top && centerY <= zone.bottom
    }

    data class AnalysisResult(
        val rapido: FareResult?,
        val uber: FareResult?,
        val isDoublePing: Boolean,
        val allDetectedText: List<String>
    ) {
        fun hasAnyResult(): Boolean = rapido != null || uber != null
        
        fun formatDisplay(): String {
            val results = mutableListOf<String>()
            
            rapido?.let { fare ->
                val emoji = if (fare.isProfitable) "🟢" else "🔴"
                val text = if (fare.bonus > 0) {
                    "$emoji RAPIDO: ₹%.1f/km (+₹%.0f bonus)".format(fare.profitPerKm, fare.bonus)
                } else {
                    "$emoji RAPIDO: ₹%.1f/km".format(fare.profitPerKm)
                }
                results.add(text)
            }
            
            uber?.let { fare ->
                if (fare.isBlocked) {
                    results.add("⚫ UBER: PRICE BLOCKED")
                } else {
                    val emoji = if (fare.isProfitable) "🟢" else "🔴"
                    val text = if (fare.bonus > 0) {
                        "$emoji UBER: ₹%.1f/km (+₹%.0f surge)".format(fare.profitPerKm, fare.bonus)
                    } else {
                        "$emoji UBER: ₹%.1f/km".format(fare.profitPerKm)
                    }
                    results.add(text)
                }
            }
            
            return if (results.isEmpty()) "Scanning..." else results.joinToString("\n")
        }
    }
}
