/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.twofortyfouram.locale.api.Intent.EXTRA_STRING_BLURB
import net.nurik.roman.muzei.R

/**
 * A minimal EDIT_SETTINGS activity for a Tasker Plugin
 */
class TaskerSettingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent().apply {
            putExtra(EXTRA_STRING_BLURB, getString(R.string.action_next_artwork))
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
