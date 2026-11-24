
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
try {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
    $request = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::ReadLine())) | ConvertFrom-Json
    $walker = [Windows.Automation.TreeWalker]::ControlViewWalker

    function Get-Identity($current) {
        $owner = [Diagnostics.Process]::GetProcessById($current.ProcessId)
        try {
            @{
                handle = [long]$current.NativeWindowHandle
                pid = $owner.Id
                started = $owner.StartTime.ToUniversalTime().Ticks.ToString()
                title = $current.Name
                app = $owner.ProcessName
            }
        } finally { $owner.Dispose() }
    }
    function Get-Actions($element, $current) {
        $pattern = $null
        if ($current.IsEnabled -and -not $current.IsOffscreen -and -not $current.IsPassword) {
            if ($element.TryGetCurrentPattern([Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) { 'invoke' }
            if (($current.ControlType -in [Windows.Automation.ControlType]::Edit, [Windows.Automation.ControlType]::Document) -and
                $element.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern, [ref]$pattern) -and
                -not $pattern.Current.IsReadOnly) {
                'set_text'
                'submit_text'
            }
            if ($element.TryGetCurrentPattern([Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern) -and
                $pattern.Current.VerticallyScrollable) { 'scroll_up'; 'scroll_down' }
        }
    }
    function Get-ElementInfo($element, $current) {
        @{
            id = $element.GetRuntimeId() -join ':'
            name = ([string]$current.Name).Substring(0, [Math]::Min(120, ([string]$current.Name).Length))
            kind = $current.ControlType.ProgrammaticName.Replace('ControlType.', '')
            actions = @(Get-Actions $element $current)
        }
    }

    function Get-FieldTexts($item) {
        $pattern = $null
        $value = ''
        if ($item.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
            $value = [string]$pattern.Current.Value
        }
        if ($item.TryGetCurrentPattern([Windows.Automation.TextPattern]::Pattern, [ref]$pattern)) {
            $documentText = ([string]$pattern.DocumentRange.GetText(1024)).TrimEnd([char]13, [char]10)
            $documentText
            # Some custom editors expose the placeholder through ValuePattern.
            # Ignore it only when the document provider independently reports an empty field.
            if ($documentText.Length -eq 0 -and $value -ceq [string]$item.Current.Name) { $value = '' }
        }
        $value
    }
    function Test-ApprovedDraft($item, [string]$text, [bool]$visualApproval) {
        if (-not $text) { return $false }
        $known = @(Get-FieldTexts $item | Where-Object { $_ })
        # With visual approval, an opaque provider is allowed. A conflicting readable value always blocks Enter.
        if ($known.Count -eq 0) { return $visualApproval }
        return (@($known | Where-Object { $_ -cne $text }).Count -eq 0)
    }
    function Test-FieldText($item, [string]$text) {
        for ($attempt = 0; $attempt -lt 7; $attempt++) {
            if ($text -cin @(Get-FieldTexts $item)) { return $true }
            Start-Sleep -Milliseconds 85
        }
        return $false
    }
    function Initialize-Keyboard {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class PrismWindowKeyboard {
    [StructLayout(LayoutKind.Sequential)]
    private struct Key {
        public ushort Virtual, Scan;
        public uint Flags, Time;
        public UIntPtr Extra;
    }
    [StructLayout(LayoutKind.Explicit, Size = 32)]
    private struct Union {
        [FieldOffset(0)] public Key Key;
        [FieldOffset(0)] public Mouse Mouse;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct Mouse {
        public int X, Y;
        public uint Data, Flags, Time;
        public UIntPtr Extra;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct Input { public uint Type; public Union Data; }

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll")]
    public static extern bool SetPhysicalCursorPos(int x, int y);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, Input[] input, int size);

    private static void Press(IntPtr target, ushort key, ushort scan, uint flags) {
        if (GetForegroundWindow() != target)
            throw new InvalidOperationException("The approved window lost focus.");
        foreach (int modifier in new[] { 0x10, 0x11, 0x12, 0x5B, 0x5C }) {
            if (GetAsyncKeyState(modifier) < 0)
                throw new InvalidOperationException("Release the keyboard modifiers before retrying.");
        }
        var down = new Input { Type = 1, Data = new Union { Key = new Key { Virtual = key, Scan = scan, Flags = flags } } };
        var up = down;
        up.Data.Key.Flags |= 2;
        if (SendInput(2, new[] { down, up }, Marshal.SizeOf(typeof(Input))) != 2)
            throw new InvalidOperationException("Windows did not accept the keyboard input.");
    }

    public static void TypeText(IntPtr target, string text) {
        foreach (char character in text) Press(target, 0, character, 4);
    }

    public static void Enter(IntPtr target) { Press(target, 13, 0, 0); }

    public static void Click(IntPtr target) {
        if (GetForegroundWindow() != target) throw new InvalidOperationException("The approved window lost focus.");
        var down = new Input { Type = 0, Data = new Union { Mouse = new Mouse { Flags = 2 } } };
        var up = down;
        up.Data.Mouse.Flags = 4;
        if (SendInput(2, new[] { down, up }, Marshal.SizeOf(typeof(Input))) != 2)
            throw new InvalidOperationException("Windows did not accept the click.");
    }
}
'@
    }
    function Test-ApprovedElement($candidate, [string]$expectedId) {
        for ($depth = 0; $depth -lt 8 -and $null -ne $candidate; $depth++) {
            if (($candidate.GetRuntimeId() -join ':') -eq $expectedId) { return $true }
            $control = $candidate.Current.ControlType
            if ($control -in [Windows.Automation.ControlType]::Button, [Windows.Automation.ControlType]::Hyperlink,
                [Windows.Automation.ControlType]::MenuItem, [Windows.Automation.ControlType]::CheckBox,
                [Windows.Automation.ControlType]::RadioButton, [Windows.Automation.ControlType]::SplitButton) { return $false }
            $candidate = $walker.GetParent($candidate)
        }
        return $false
    }
    function Focus-ApprovedField($item, [IntPtr]$handle, [string]$expectedId) {
        [PrismWindowKeyboard]::SetForegroundWindow($handle) | Out-Null
        for ($attempt = 0; $attempt -lt 30 -and [PrismWindowKeyboard]::GetForegroundWindow() -ne $handle; $attempt++) {
            Start-Sleep -Milliseconds 125
        }
        if ([PrismWindowKeyboard]::GetForegroundWindow() -ne $handle) {
            throw 'Windows не переключила окно. При подтверждении сразу переключитесь в выбранное окно, если оно не открылось само.'
        }
        try { $item.SetFocus() } catch [InvalidOperationException] {}
        Start-Sleep -Milliseconds 140
        if (-not (Test-ApprovedElement ([Windows.Automation.AutomationElement]::FocusedElement) $expectedId)) {
            $rectangle = $item.Current.BoundingRectangle
            if ($rectangle.IsEmpty -or $rectangle.Width -lt 4 -or $rectangle.Height -lt 4) {
                throw 'Координаты поля ввода недоступны.'
            }
            $x = [int]($rectangle.Left + $rectangle.Width / 2)
            $y = [int]($rectangle.Top + $rectangle.Height / 2)
            $point = [Windows.Point]::new($x, $y)
            $hit = [Windows.Automation.AutomationElement]::FromPoint($point)
            if ($root.Current.Name -cne $target.title) { throw 'PRISM_WINDOW_TITLE_CHANGED' }
            if (-not (Test-ApprovedElement $hit $expectedId) -or
                [PrismWindowKeyboard]::GetForegroundWindow() -ne $handle) {
                throw 'Поле закрыто другим элементом или выбранное окно изменилось. Клик отменён.'
            }
            if (-not [PrismWindowKeyboard]::SetPhysicalCursorPos($x, $y)) { throw 'Не удалось переместить указатель к полю.' }
            # Hover may reveal another control. Check the hit target a second time before clicking.
            $hit = [Windows.Automation.AutomationElement]::FromPoint($point)
            if ($root.Current.Name -cne $target.title) { throw 'PRISM_WINDOW_TITLE_CHANGED' }
            if (-not (Test-ApprovedElement $hit $expectedId)) {
                throw 'Элемент под указателем изменился. Клик отменён.'
            }
            [PrismWindowKeyboard]::Click($handle)
            Start-Sleep -Milliseconds 160
        }
        if ($root.Current.Name -cne $target.title) { throw 'PRISM_WINDOW_TITLE_CHANGED' }
        if ([PrismWindowKeyboard]::GetForegroundWindow() -ne $handle -or
            -not (Test-ApprovedElement ([Windows.Automation.AutomationElement]::FocusedElement) $expectedId)) {
            throw 'Выбранное поле не получило фокус. Клавиши не были нажаты.'
        }
    }

    if ($request.operation -eq 'list') {
        $result = @(
            $item = $walker.GetFirstChild([Windows.Automation.AutomationElement]::RootElement)
            $examined = 0
            while ($null -ne $item -and $examined++ -lt 200) {
                try {
                    $current = $item.Current
                    if ($current.NativeWindowHandle -ne 0 -and -not $current.IsOffscreen -and
                        $current.ProcessId -ne $request.clientPid -and $current.Name -and
                        $current.ControlType -eq [Windows.Automation.ControlType]::Window) {
                        $identity = Get-Identity $current
                        if ($identity.app -notin @('PrismAI', 'explorer') -or
                            ($identity.app -eq 'explorer' -and $current.ClassName -eq 'CabinetWClass')) { $identity }
                    }
                } catch [Windows.Automation.ElementNotAvailableException] {} catch [System.ComponentModel.Win32Exception] {} catch [System.ArgumentException] {}
                $item = $walker.GetNextSibling($item)
            }
        )
    } else {
        $target = $request.window | ConvertFrom-Json
        $root = [Windows.Automation.AutomationElement]::FromHandle([IntPtr]([long]$target.handle))
        $rootCurrent = $root.Current
        $identity = Get-Identity $rootCurrent
        if ($identity.pid -ne $target.pid -or $identity.started -ne $target.started -or
            $identity.pid -eq $request.clientPid -or $identity.app -eq 'PrismAI' -or
            ($identity.app -eq 'explorer' -and $rootCurrent.ClassName -ne 'CabinetWClass') -or $rootCurrent.IsOffscreen) {
            throw 'The selected window changed, closed, or is minimized. Select the window again.'
        }
        if ($request.operation -eq 'screenshot') {
            Add-Type -AssemblyName System.Drawing
            Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

public static class PrismWindowSnapshot {
    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr window, out Rect bounds);
    [DllImport("user32.dll")]
    private static extern bool PrintWindow(IntPtr window, IntPtr deviceContext, uint flags);

    public static string Capture(IntPtr window) {
        Rect bounds;
        if (!GetWindowRect(window, out bounds)) throw new InvalidOperationException("Window bounds are unavailable.");
        int width = bounds.Right - bounds.Left, height = bounds.Bottom - bounds.Top;
        if (width < 1 || height < 1 || width > 6000 || height > 6000 || (long)width * height > 16000000)
            throw new InvalidOperationException("Window is too large to capture.");
        using (var full = new Bitmap(width, height, PixelFormat.Format24bppRgb)) {
            using (var graphics = Graphics.FromImage(full)) {
                IntPtr context = graphics.GetHdc();
                try {
                    if (!PrintWindow(window, context, 2))
                        throw new InvalidOperationException("The window does not support snapshots.");
                } finally { graphics.ReleaseHdc(context); }
            }
            double ratio = Math.Min(1.0, 1024.0 / Math.Max(width, height));
            using (var scaled = new Bitmap(full, Math.Max(1, (int)(width * ratio)), Math.Max(1, (int)(height * ratio))))
            using (var output = new MemoryStream()) {
                scaled.Save(output, ImageFormat.Png);
                return Convert.ToBase64String(output.ToArray());
            }
        }
    }
}
'@
            $snapshot = [PrismWindowSnapshot]::Capture([IntPtr]([long]$target.handle))
            [Console]::WriteLine((ConvertTo-Json -InputObject $snapshot -Compress))
            exit 0
        }
        $expected = if ($request.element) { $request.element | ConvertFrom-Json }
        if ($null -ne $expected -and $identity.title -cne $target.title) {
            throw 'PRISM_WINDOW_TITLE_CHANGED'
        }
        $queue = New-Object 'System.Collections.Generic.Queue[Windows.Automation.AutomationElement]'
        $queue.Enqueue($root)
        $items = New-Object 'System.Collections.Generic.List[object]'
        $found = $false
        $actionResult = $true
        $visited = 0
        $enqueued = 220
        while ($queue.Count -gt 0 -and $visited++ -lt 220) {
            $item = $queue.Dequeue()
            try {
                $current = $item.Current
                if (-not $current.IsOffscreen -and -not $current.IsPassword) {
                    $info = Get-ElementInfo $item $current
                    if ($request.operation -eq 'inspect') {
                        if (($info.name -or $info.actions.Count -gt 0) -and $items.Count -lt 80) { $items.Add($info) }
                        if ($items.Count -eq 80) { break }
                    } elseif ($info.id -eq $expected.id) {
                        if ($current.ProcessId -ne $target.pid -or $info.name -cne $expected.name -or
                            $info.kind -ne $expected.kind -or $request.operation -notin $info.actions) {
                            throw 'The control changed since inspection. Inspect it again.'
                        }
                        switch ($request.operation) {
                            'invoke' {
                                $item.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern).Invoke()
                            }
                            'set_text' {
                                $field = $item.GetCurrentPattern([Windows.Automation.ValuePattern]::Pattern)
                                $text = [string]$request.text
                                if (@(Get-FieldTexts $item | Where-Object { $_ -and $_ -cne $text }).Count -gt 0) {
                                    throw 'В поле уже есть другой текст. Ввод отменён, чтобы сохранить черновик.'
                                }
                                if ($text -cnotin @(Get-FieldTexts $item)) {
                                    try { $field.SetValue($text) } catch [InvalidOperationException] {}
                                }
                                if (-not (Test-FieldText $item $text)) {
                                    if ($text -match '[\u0000-\u001F\u007F]') {
                                        throw 'Запасной ввод с клавиатуры поддерживает текст в одну строку. Поле не подтвердило исходный ввод.'
                                    }
                                    if (@(Get-FieldTexts $item | Where-Object { $_ }).Count -gt 0) {
                                        throw 'Поле вернуло другой текст. Повторный ввод отменён, чтобы не повредить черновик.'
                                    }
                                    Initialize-Keyboard
                                    $handle = [IntPtr]([long]$target.handle)
                                    Focus-ApprovedField $item $handle $expected.id
                                    if (@(Get-FieldTexts $item | Where-Object { $_ }).Count -gt 0) {
                                        throw 'В поле появился текст. Ввод отменён, чтобы сохранить черновик.'
                                    }
                                    [PrismWindowKeyboard]::TypeText($handle, $text)
                                    if (-not (Test-FieldText $item $text)) {
                                        $actionResult = 'typed_unverified'
                                    }
                                }
                            }
                            'submit_text' {
                                $text = [string]$request.text
                                if (-not (Test-ApprovedDraft $item $text $request.visualApproval)) {
                                    throw 'Черновик изменился или недоступен для проверки. Нажатие Enter отменено.'
                                }
                                Initialize-Keyboard
                                $handle = [IntPtr]([long]$target.handle)
                                Focus-ApprovedField $item $handle $expected.id
                                if (-not (Test-ApprovedDraft $item $text $request.visualApproval)) { throw 'Черновик изменился. Нажатие Enter отменено.' }
                                [PrismWindowKeyboard]::Enter($handle)
                            }
                            { $_ -in 'scroll_up', 'scroll_down' } {
                                $direction = if ($_ -eq 'scroll_up') { 'LargeDecrement' } else { 'LargeIncrement' }
                                $item.GetCurrentPattern([Windows.Automation.ScrollPattern]::Pattern).Scroll(
                                    [Windows.Automation.ScrollAmount]::NoAmount, [Windows.Automation.ScrollAmount]$direction)
                            }
                            default { throw 'Unsupported operation.' }
                        }
                        $found = $true
                        break
                    }
                }
                $child = $walker.GetFirstChild($item)
                while ($null -ne $child -and $enqueued -gt 0) {
                    $queue.Enqueue($child)
                    $enqueued--
                    if ($enqueued -gt 0) { $child = $walker.GetNextSibling($child) }
                }
            } catch [Windows.Automation.ElementNotAvailableException] {}
        }
        if ($request.operation -eq 'inspect') { $result = @{ title = $identity.title; elements = $items.ToArray() } }
        elseif ($found) { $result = $actionResult }
        else { throw 'The control is no longer present. Inspect the window again.' }
    }
    [Console]::WriteLine((ConvertTo-Json -InputObject $result -Depth 5 -Compress))
} catch {
    [Console]::WriteLine($_.Exception.Message)
    exit 1
}
