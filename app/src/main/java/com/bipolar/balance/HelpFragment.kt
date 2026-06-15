package com.bipolar.balance

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bipolar.balance.databinding.FragmentHelpBinding

class HelpFragment : Fragment() {

    private var _b: FragmentHelpBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentHelpBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.tvHomepage.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fortalis-dev.github.io")))
        }

        b.tvContactEmail.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:fortalis@proton.me")
                putExtra(Intent.EXTRA_SUBJECT, "My Balance app")
            }
            startActivity(Intent.createChooser(intent, null))
        }

        b.tvBuyCoffee.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/fortalis")))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
